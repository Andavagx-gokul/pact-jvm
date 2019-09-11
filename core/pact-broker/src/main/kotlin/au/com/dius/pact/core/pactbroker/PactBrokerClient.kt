package au.com.dius.pact.core.pactbroker

import au.com.dius.pact.com.github.michaelbull.result.*
import au.com.dius.pact.core.support.ContentTypeUtils
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.jsonArray
import com.github.salomonbrys.kotson.jsonObject
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.set
import com.github.salomonbrys.kotson.string
import com.github.salomonbrys.kotson.toJson
import com.google.common.net.UrlEscapers.urlPathSegmentEscaper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KLogging
import org.apache.http.entity.ContentType
import org.dmfs.rfc3986.encoding.Precoded
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URLDecoder
import java.util.function.BiFunction
import java.util.function.Consumer

/**
 * Wraps the response for a Pact from the broker with the link data associated with the Pact document.
 */
data class PactResponse(val pactFile: JsonObject, val links: Map<String, Map<String, Any>>)

sealed class TestResult {
  object Ok : TestResult() {
    override fun toBoolean() = true

    override fun merge(result: TestResult) = when (result) {
      is Ok -> this
      is Failed -> result
    }
  }

  data class Failed(var results: List<Map<String, Any?>> = emptyList(), val description: String = "") : TestResult() {
    override fun toBoolean() = false

    override fun merge(result: TestResult) = when (result) {
      is Ok -> this
      is Failed -> Failed(results + result.results, when {
        description.isNotEmpty() && result.description.isNotEmpty() && description != result.description ->
          "$description, ${result.description}"
        description.isNotEmpty() -> description
        else -> result.description
      })
    }
  }

  abstract fun toBoolean(): Boolean
  abstract fun merge(result: TestResult): TestResult

  companion object {
    fun fromBoolean(result: Boolean) = if (result) Ok else Failed()
  }
}

/**
 * Client for the pact broker service
 */
open class PactBrokerClient(val pactBrokerUrl: String, val options: Map<String, Any>) {

  constructor(pactBrokerUrl: String) : this(pactBrokerUrl, mapOf())

  /**
   * Fetches all consumers for the given provider
   */
  open fun fetchConsumers(provider: String): List<PactBrokerConsumer> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerConsumer>()
      halClient.navigate(mapOf("provider" to provider), LATEST_PROVIDER_PACTS).forAll(PACTS, Consumer { pact ->
        val href = Precoded(pact["href"].toString()).decoded().toString()
        val name = pact["name"].toString()
        if (options.containsKey("authentication")) {
          consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, options["authentication"] as List<String>))
        } else {
          consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl))
        }
      })
      consumers
    } catch (e: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  /**
   * Fetches all consumers for the given provider and tag
   */
  open fun fetchConsumersWithTag(provider: String, tag: String): List<PactBrokerConsumer> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerConsumer>()
      halClient.navigate(mapOf("provider" to provider, "tag" to tag), LATEST_PROVIDER_PACTS_WITH_TAG)
        .forAll(PACTS, Consumer { pact ->
        val href = Precoded(pact["href"].toString()).decoded().toString()
        val name = pact["name"].toString()
        if (options.containsKey("authentication")) {
          consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, options["authentication"] as List<String>, tag))
        } else {
          consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, emptyList(), tag))
        }
      })
      consumers
    } catch (e: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  /**
   * Uploads the given pact file to the broker, and optionally applies any tags
   */
  @JvmOverloads
  @Deprecated("Renamed to uploadContract",
    ReplaceWith("uploadContract(pactFile, unescapedVersion, tags)"))
  open fun uploadPactFile(pactFile: File, unescapedVersion: String, tags: List<String> = emptyList()) =
    uploadContract(pactFile, unescapedVersion, tags)

  /**
   * Uploads the given contract file to the broker, and optionally applies any tags
   */
  @JvmOverloads
  open fun uploadContract(pactFile: File, unescapedVersion: String, tags: List<String> = emptyList()): Result<String, String> {
    val version = urlPathSegmentEscaper().escape(unescapedVersion)
    val pactText = pactFile.readText()
    val callback = { result: String, status: String ->
      if (result == "OK") {
        Ok(status)
      } else {
        Err("FAILED! $status")
      }
    }
    return if (ContentTypeUtils.detectContentType(pactText) == "application/json") {
      uploadJsonContract(pactText, version, tags, callback)
    } else {
      uploadYamlContract(pactText, version, tags, callback)
    }
  }

  private fun uploadYamlContract(
    pactText: String,
    version: String,
    tags: List<String> = emptyList(),
    callback: (String, String) -> Result<String, String>
  ): Result<String, String> {
    val yaml = Yaml()
    val doc = yaml.load<Map<String, Any?>>(pactText)
    return if (doc.containsKey("openapi")) {
      val info = doc["info"]
      if (info is Map<*, *>) {
        val providerName = urlPathSegmentEscaper().escape(info["title"].toString())
        var ver = info["version"].toString()
        ver = if (ver.isEmpty()) {
          version
        } else {
          urlPathSegmentEscaper().escape(ver)
        }
        uploadOasContract(tags, ver, providerName, pactText, callback, "application/yaml")
      } else {
        Err("OAS info section is mandatory")
      }
    } else {
      Err("Contract is not an OAS format")
    }
  }

  private fun uploadJsonContract(
    pactText: String,
    version: String,
    tags: List<String> = emptyList(),
    callback: (String, String) -> Result<String, String>
  ): Result<String, String> {
    val json = JsonParser().parse(pactText)
    return if (json.isJsonObject && json.obj.has("openapi")) {
      val providerName = urlPathSegmentEscaper().escape(json["info"]["title"].string)
      var ver = version
      if (json["info"]["version"].isJsonPrimitive && json["info"]["version"].asJsonPrimitive.asString.isNotEmpty()) {
        ver = urlPathSegmentEscaper().escape(json["info"]["version"].asJsonPrimitive.asString)
      }
      uploadOasContract(tags, ver, providerName, pactText, callback, "application/json")
    } else {
      val providerName = urlPathSegmentEscaper().escape(json["provider"]["name"].string)
      val consumerName = urlPathSegmentEscaper().escape(json["consumer"]["name"].string)
      uploadPactContract(tags, version, providerName, consumerName, pactText, callback)
    }
  }

  private fun uploadOasContract(
    tags: List<String>,
    version: String,
    providerName: String,
    pactText: String, callback: (String, String) -> Result<String, String>,
    contentType: String
  ): Result<String, String> {
    val halClient = newHalClient()
    if (tags.isNotEmpty() && providerName.isNotEmpty()) {
      uploadTags(halClient, providerName, version, tags)
    }
    return Result.of {
      val result = halClient.uploadDocument("/oas/provider/$providerName/version/$version", pactText,
        BiFunction { a, b -> callback(a, b) }, false, contentType) as Result<String, String>
      return when (result) {
        is Ok<*> -> result
        else -> throw Exception(result.getError())
      }
    }.mapError { err -> err.message.toString() }
  }

  private fun uploadPactContract(
    tags: List<String>,
    version: String,
    providerName: String,
    consumerName: String,
    pactText: String,
    callback: (String, String) -> Result<String, String>
  ): Result<String, String> {
    val halClient = newHalClient()
    if (tags.isNotEmpty() && consumerName.isNotEmpty()) {
      uploadTags(halClient, consumerName, version, tags)
    }
    return Result.of {
      val result = halClient.uploadDocumentToLink("pb:publish-pact", pactText, mapOf(),
        callback, false, ContentType.APPLICATION_JSON.toString())
      return when (result) {
        is Ok<*> -> result
        else -> throw Exception(result.getError())
      }
    }.mapError { err -> err.message.toString() }
  }

  open fun getUrlForProvider(providerName: String, tag: String): String? {
    val halClient = newHalClient()
    if (tag.isEmpty() || tag == "latest") {
      halClient.navigate(mapOf("provider" to providerName), LATEST_PROVIDER_PACTS)
    } else {
      halClient.navigate(mapOf("provider" to providerName, "tag" to tag), LATEST_PROVIDER_PACTS_WITH_TAG)
    }
    return halClient.linkUrl(PACTS)
  }

  open fun fetchPact(url: String): PactResponse {
    val halDoc = newHalClient().fetch(url).obj
    return PactResponse(halDoc, HalClient.asMap(halDoc["_links"].obj) as Map<String, Map<String, Any>>)
  }

  open fun newHalClient(): IHalClient = HalClient(pactBrokerUrl, options)

  /**
   * Publishes the result to the "pb:publish-verification-results" link in the document attributes.
   */
  @JvmOverloads
  open fun publishVerificationResults(
    docAttributes: Map<String, Map<String, Any>>,
    result: TestResult,
    version: String,
    buildUrl: String? = null
  ): Result<Boolean, Exception> {
    val halClient = newHalClient()
    val publishLink = docAttributes.mapKeys { it.key.toLowerCase() } ["pb:publish-verification-results"] // ktlint-disable curly-spacing
    return if (publishLink != null) {
      val jsonObject = buildPayload(result, version, buildUrl)

      val lowercaseMap = publishLink.mapKeys { it.key.toLowerCase() }
      if (lowercaseMap.containsKey("href")) {
        halClient.postJson(lowercaseMap["href"].toString(), jsonObject.toString())
      } else {
        Err(RuntimeException("Unable to publish verification results as there is no " +
          "pb:publish-verification-results link"))
      }
    } else {
      Err(RuntimeException("Unable to publish verification results as there is no " +
        "pb:publish-verification-results link"))
    }
  }

  fun buildPayload(result: TestResult, version: String, buildUrl: String?): JsonObject {
    val jsonObject = jsonObject("success" to result.toBoolean(), "providerApplicationVersion" to version)
    if (buildUrl != null) {
      jsonObject.add("buildUrl", buildUrl.toJson())
    }

    logger.debug { "Test result = $result" }
    if (result is TestResult.Failed && result.results.isNotEmpty()) {
      val values = result.results
        .groupBy { it["interactionId"] }
        .map { mismatches ->
          val values = mismatches.value
            .filter { !it.containsKey("exception") }
            .flatMap { mismatch ->
              when (mismatch["type"]) {
                "body" -> {
                  when (val bodyMismatches = mismatch["comparison"]) {
                    is Map<*, *> -> bodyMismatches.entries.filter { it.key != "diff" }.flatMap { entry ->
                      val values = entry.value as List<Map<String, Any>>
                      values.map {
                        jsonObject("attribute" to "body", "identifier" to entry.key, "description" to it["mismatch"],
                          "diff" to it["diff"])
                      }
                    }
                    else -> listOf(jsonObject("attribute" to "body", "description" to bodyMismatches.toString()))
                  }
                }
                "status" -> listOf(jsonObject("attribute" to "status", "description" to mismatch["description"]))
                "header" -> {
                  listOf(jsonObject(mismatch.filter { it.key != "interactionId" }
                    .map {
                      if (it.key == "type") {
                        "attribute" to it.value
                      } else {
                        it.toPair()
                      }
                    }))
                }
                "metadata" -> {
                  listOf(jsonObject(mismatch.filter { it.key != "interactionId" }
                    .flatMap {
                      when {
                        it.key == "type" -> listOf("attribute" to it.value)
                        else -> listOf("identifier" to it.key, "description" to it.value)
                      }
                    }))
                }
                else -> listOf(jsonObject(
                  mismatch.filterNot { it.key == "interactionId" || it.key == "type" }.entries.map {
                    it.toPair()
                  }
                ))
              }
            }
          val interactionJson = jsonObject("interactionId" to mismatches.key, "success" to false,
            "description" to result.description,
            "mismatches" to jsonArray(values)
          )

          val exceptionDetails = mismatches.value.find { it.containsKey("exception") }
          if (exceptionDetails != null) {
            val exp = exceptionDetails["exception"] as Exception
            interactionJson["exception"] = jsonObject("message" to exp.message,
              "exceptionClass" to exp.javaClass.name)
          }

          interactionJson
        }
      jsonObject.add("testResults", jsonArray(values))
    }
    return jsonObject
  }

  /**
   * Fetches the consumers of the provider that have no associated tag
   */
  open fun fetchLatestConsumersWithNoTag(provider: String): List<PactBrokerConsumer> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerConsumer>()
      halClient.navigate(mapOf("provider" to provider), LATEST_PROVIDER_PACTS_WITH_NO_TAG)
        .forAll(PACTS, Consumer { pact ->
          val href = URLDecoder.decode(pact["href"].toString(), UTF8)
          val name = pact["name"].toString()
          if (options.containsKey("authentication")) {
            consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, options["authentication"] as List<String>))
          } else {
            consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, emptyList()))
          }
        })
      consumers
    } catch (_: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  companion object : KLogging() {
    const val LATEST_PROVIDER_PACTS_WITH_NO_TAG = "pb:latest-untagged-pact-version"
    const val LATEST_PROVIDER_PACTS = "pb:latest-provider-pacts"
    const val LATEST_PROVIDER_PACTS_WITH_TAG = "pb:latest-provider-pacts-with-tag"
    const val PACTS = "pacts"
    const val UTF8 = "UTF-8"

    fun uploadTags(halClient: IHalClient, consumerName: String, version: String, tags: List<String>) {
      tags.forEach {
        val tag = urlPathSegmentEscaper().escape(it)
        halClient.uploadJson("/pacticipants/$consumerName/versions/$version/tags/$tag", "",
          BiFunction { _, _ -> null }, false)
      }
    }
  }
}
