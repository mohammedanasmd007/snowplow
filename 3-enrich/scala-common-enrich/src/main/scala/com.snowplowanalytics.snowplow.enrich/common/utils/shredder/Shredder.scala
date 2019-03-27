/*
 * Copyright (c) 2014-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package utils
package shredder

import scala.collection.JavaConversions._

import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.fge.jsonschema.core.report.ProcessingMessage
import com.snowplowanalytics.iglu.client.{JsonSchemaPair, Resolver, SchemaCriterion}
import com.snowplowanalytics.iglu.client.validation.ProcessingMessageMethods._
import com.snowplowanalytics.iglu.client.validation.ValidatableJsonMethods._

import outputs.EnrichedEvent

/**
 * The shredder takes the two fields containing JSONs (contexts and unstructured event properties)
 * and "shreds" their contents into a List of JsonNodes ready for loading into dedicated tables in
 * the database.
 */
object Shredder {

  /** A (possibly empty) list of JsonNodes */
  type JsonNodes = List[JsonNode]

  // All shredded JSONs have the events type (aka table) as their ultimate parent
  private val TypeHierarchyRoot = "events"

  // Self-describing schema for a ue_properties
  private val UePropertiesSchema =
    SchemaCriterion("com.snowplowanalytics.snowplow", "unstruct_event", "jsonschema", 1, 0)

  // Self-describing schema for a contexts
  private val ContextsSchema =
    SchemaCriterion("com.snowplowanalytics.snowplow", "contexts", "jsonschema", 1, 0)

  /**
   * Shred the EnrichedEvent's two fields which contain JSONs: contexts and unstructured event
   * properties. By shredding we mean:
   * 1. Verify the two fields contain valid JSONs
   * 2. Validate they conform to JSON Schema
   * 3. For the contexts, break the singular JsonNode into a List of individual context JsonNodes
   * 4. Collect the unstructured event and contexts into a singular List
   * @param event The Snowplow enriched event to shred JSONs from
   * @param resolver Our implicit Iglu Resolver, for schema lookups
   * @return a Validation containing on Success a List (possible empty) of JsonNodes and on Failure
   * a NonEmptyList of JsonNodes containing error messages
   */
  def shred(event: EnrichedEvent)(
    implicit resolver: Resolver
  ): ValidatedNel[ProcessingMessage, List[JsonSchemaPair]] = {
    // Define what we know so far of the type hierarchy.
    val partialHierarchy = makePartialHierarchy(event.event_id, event.collector_tstamp)

    // Get our unstructured event and Lists of contexts and derived_contexts
    val ue = extractAndValidateUnstructEvent(event)
    val c = extractAndValidateCustomContexts(event)
    val dc = extractAndValidateDerivedContexts(event)

    // Joining all validated JSONs into a single validated List[JsonNode], collecting Failures too
    val all = ue |+| c |+| dc

    all.map { jsonSchemaPairs =>
      jsonSchemaPairs.map(pair => attachMetadata(pair, partialHierarchy))
    }
  }

  /**
   * Extract unstruct event out of EnrichedEvent and validate against it's schema
   * @param event The Snowplow enriched event to find unstruct event in
   * @param resolver iglu resolver
   * @return validated list (empty or single-element) of pairs consist of unstruct event schema and
   * node
   */
  def extractAndValidateUnstructEvent(event: EnrichedEvent)(
    implicit resolver: Resolver
  ): ValidatedNel[ProcessingMessage, List[JsonSchemaPair]] = {
    val extracted = flatten(extractUnstructEvent(event))
    validate(extracted)
  }

  /**
   * Extract list of custom contexts out of string and validate each against its schema
   * @param event The Snowplow enriched event to extract custom context JSONs from
   * @param resolver iglu resolver
   * @return validated list of pairs consist of schema and node
   */
  def extractAndValidateCustomContexts(event: EnrichedEvent)(
    implicit resolver: Resolver
  ): ValidatedNel[ProcessingMessage, List[JsonSchemaPair]] =
    extractAndValidateContexts(event.contexts, "context")

  /**
   * Extract list of derived contexts out of string and validate each against its schema
   * @param event The Snowplow enriched event to extract custom context JSONs from
   * @param resolver iglu resolver
   * @return validated list of pairs consist of schema and node
   */
  def extractAndValidateDerivedContexts(event: EnrichedEvent)(
    implicit resolver: Resolver
  ): ValidatedNel[ProcessingMessage, List[JsonSchemaPair]] =
    extractAndValidateContexts(event.derived_contexts, "derived_contexts")

  /**
   * Extract list of contexts out of string and validate each against its schema
   * @param json string supposed to contain Snowplow Contexts object
   * @param field field where object is came from (used only for error log)
   * @param resolver iglu resolver
   * @return validated list of pairs consist of schema and node
   */
  private[shredder] def extractAndValidateContexts(json: String, field: String)(
    implicit resolver: Resolver
  ): ValidatedNel[ProcessingMessage, List[JsonSchemaPair]] = {
    val extracted = flatten(extractContexts(json, field))
    validate(extracted)
  }

  /**
   * Extract unstruct event as JsonNode. Extraction involves validation against schema.
   * Event itself extracted as List (empty or with single element)
   * @param event The Snowplow enriched event to shred JSONs from
   * @param resolver Our implicit Iglu resolver, for schema lookups
   * @return a Validation containing on Success a List (possible empty) of JsonNodes and on Failure
   * a NonEmptyList of JsonNodes containing error messages
   */
  def extractUnstructEvent(event: EnrichedEvent)(
    implicit resolver: Resolver
  ): Option[ValidatedNel[ProcessingMessage, JsonNodes]] =
    for {
      v <- extractAndValidateJson("ue_properties", UePropertiesSchema, Option(event.unstruct_event))
    } yield
      for {
        j <- v
        l = List(j)
      } yield l

  /**
   * Extract list of contexts out of string. Extraction involves validation against schema
   * @param json string with contexts object
   * @param field field where object is came from (used only for error log)
   * @param resolver Our implicit Iglu resolver, for schema lookups
   * @return an Optional Validation containing on Success a List (possible empty) of JsonNodes
   * and on Failure a NonEmptyList of JsonNodes containing error messages
   */
  private[shredder] def extractContexts(json: String, field: String)(
    implicit resolver: Resolver
  ): Option[ValidatedNel[ProcessingMessage, JsonNodes]] =
    for {
      v <- extractAndValidateJson(field, ContextsSchema, Option(json))
    } yield
      for {
        j <- v
        l = j.iterator.toList
      } yield l

  /**
   * Fetch Iglu Schema for each [[JsonNode]] in [[ValidatedNelMessage]] and validate this node
   * against it
   * @param validatedJsons list of valid JSONs supposed to be Self-describing
   * @return validated list of pairs consist of schema and node
   */
  private[shredder] def validate(validatedJsons: ValidatedNel[ProcessingMessage, JsonNodes])(
    implicit resolver: Resolver
  ): ValidatedNel[ProcessingMessage, List[JsonSchemaPair]] =
    validatedJsons
      .map { (jsonNodes: List[JsonNode]) =>
        jsonNodes.map(_.validateAndIdentifySchema(false) match {
          case scalaz.Success(p) => p.asRight
          case scalaz.Failure(e) => NonEmptyList.of(e.head, e.tail: _*).asLeft
        })
      }
      .toEither
      .flatMap(_.sequence)
      .toValidated

  /**
   * Flatten Option[List] to List
   * @param o Option with List
   * @return empty list in case of None, or non-empty in case of some
   */
  private[shredder] def flatten(
    o: Option[ValidatedNel[ProcessingMessage, JsonNodes]]
  ): ValidatedNel[ProcessingMessage, JsonNodes] = o match {
    case Some(vjl) => vjl
    case None => List[JsonNode]().validNel
  }

  /**
   * Convenience to make a partial TypeHierarchy. Partial because we don't have the complete
   * refTree yet.
   * @param rootId The ID of the root element
   * @param rootTstamp The timestamp of the root element
   * @return the partially complete TypeHierarchy
   */
  private[shredder] def makePartialHierarchy(rootId: String, rootTstamp: String): TypeHierarchy =
    TypeHierarchy(
      rootId = rootId,
      rootTstamp = rootTstamp,
      refRoot = TypeHierarchyRoot,
      refTree = List(TypeHierarchyRoot), // This is a partial tree. Need to complete later
      refParent = TypeHierarchyRoot // Hardcode as nested shredding not supported yet
    )

  /**
   * Adds shred-related metadata to the JSON. There are two envelopes of metadata to attach:
   * 1. schema - we replace the existing schema URI string with a full schema key object containing
   * name, vendor, format and version as separate string properties
   * 2. hierarchy - we add a new object expressing the type hierarchy for this shredded JSON
   * @param instanceSchemaPair Tuple2 containing:
   *        1. The SchemaKey identifying the schema for this JSON
   *        2. The JsonNode for this JSON
   * @param partialHierarchy The type hierarchy to attach. Partial because the refTree is still
   * incomplete
   * @return the Tuple2, with the JSON updated to contain the full schema key, plus the
   * now-finalized hierarchy
   */
  private def attachMetadata(
    instanceSchemaPair: JsonSchemaPair,
    partialHierarchy: TypeHierarchy
  ): JsonSchemaPair = {
    val (schemaKey, instance) = instanceSchemaPair

    val schemaNode = schemaKey.toJsonNode
    val hierarchyNode = {
      val full = partialHierarchy.complete(List(schemaKey.name))
      full.toJsonNode
    }

    // This might look unsafe but we're only here
    // if this instance has been validated as a
    // self-describing JSON, i.e. we can assume the
    // below structure.
    val updated = instance.asInstanceOf[ObjectNode]
    updated.replace("schema", schemaNode)
    updated.set("hierarchy", hierarchyNode)

    (schemaKey, updated)
  }

  /**
   * Extract the JSON from a String, and validate it against the supplied JSON Schema.
   * @param field The name of the field containing the JSON instance
   * @param schemaCriterion The criterion we expected this self-describing JSON to conform to
   * @param instance An Option-boxed JSON instance
   * @param resolver Our implicit Iglu Resolver, for schema lookups
   * @return an Option-boxed Validation containing either a Nel of JsonNodes error message on
   * Failure, or a singular JsonNode on success
   */
  private def extractAndValidateJson(
    field: String,
    schemaCriterion: SchemaCriterion,
    instance: Option[String]
  )(implicit resolver: Resolver): Option[ValidatedNel[ProcessingMessage, JsonNode]] =
    instance.map { i =>
      (for {
        j <- extractJson(field, i).leftMap(e => NonEmptyList.one(e.toProcessingMessage))
        v <- j.verifySchemaAndValidate(schemaCriterion, true) match {
          case scalaz.Success(j) => j.asRight
          case scalaz.Failure(e) => NonEmptyList.of(e.head, e.tail: _*).asLeft
        }
      } yield v).toValidated
    }

  /**
   * Wrapper around JsonUtils' extractJson which converts the failure to a JsonNode Nel, for
   * compatibility with subsequent JSON Schema checks.
   * @param field The name of the field containing JSON
   * @param instance The JSON instance itself
   * @return the pimped ScalazArgs
   */
  private def extractJson(
    field: String,
    instance: String
  ): Either[String, JsonNode] =
    JsonUtils.extractJsonNode(field, instance)
}