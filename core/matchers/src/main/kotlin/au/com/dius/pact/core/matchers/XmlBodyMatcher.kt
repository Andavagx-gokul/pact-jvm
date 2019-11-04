package au.com.dius.pact.core.matchers

import au.com.dius.pact.core.matchers.util.padTo
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.matchingrules.MatchingRules
import au.com.dius.pact.core.support.zipAll
import mu.KLogging
import org.apache.xerces.dom.TextImpl
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.Node.ELEMENT_NODE
import org.w3c.dom.Node.TEXT_NODE
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object XmlBodyMatcher : BodyMatcher, KLogging() {

  override fun matchBody(
    expected: OptionalBody,
    actual: OptionalBody,
    allowUnexpectedKeys: Boolean,
    matchingRules: MatchingRules
  ): List<BodyMismatch> {
    return when {
      expected.isMissing() -> emptyList()
      expected.isEmpty() && actual.isEmpty() -> emptyList()
      actual.isMissing() ->
        listOf(BodyMismatch(expected.unwrap(), null, "Expected body '${expected.value}' but was missing"))
      else -> {
        compareNode(listOf("$"), parse(expected.valueAsString()), parse(actual.valueAsString()),
          allowUnexpectedKeys, matchingRules)
      }
    }
  }

  fun parse(xmlData: String): Node {
    return if (xmlData.isEmpty()) {
      TextImpl()
    } else {
      val dbFactory = DocumentBuilderFactory.newInstance()
      val dBuilder = dbFactory.newDocumentBuilder()
      val xmlInput = InputSource(StringReader(xmlData))
      val doc = dBuilder.parse(xmlInput)
      doc.documentElement
    }
  }

  private fun appendAttribute(path: List<String>, attribute: String): List<String> {
    return path + "@$attribute"
  }

  fun compareText(
    path: List<String>,
    expected: Node,
    actual: Node,
    matchers: MatchingRules
  ): List<BodyMismatch> {
    val textpath = path + "#text"
    val expectedText = asList(expected.childNodes).filter { n -> n.nodeType == TEXT_NODE }
      .joinToString("") { n -> n.textContent.trim() }
    val actualText = asList(actual.childNodes).filter { n -> n.nodeType == TEXT_NODE }
      .joinToString("") { n -> n.textContent.trim() }
    return when {
      Matchers.matcherDefined("body", textpath, matchers) -> {
        logger.debug { "compareText: Matcher defined for path $textpath" }
        Matchers.domatch(matchers, "body", textpath, expectedText, actualText, BodyMismatchFactory)
      }
      expectedText != actualText ->
        listOf(BodyMismatch(expected, actual, "Expected value '$expectedText' but received '$actualText'",
          textpath.joinToString(".")))
      else -> emptyList()
    }
  }

  private fun asList(childNodes: NodeList?): List<Node> {
    return if (childNodes == null) {
      emptyList()
    } else {
      val list = mutableListOf<Node>()
      for (i in 0 until childNodes.length) {
        list.add(childNodes.item(i))
      }
      list
    }
  }

  private fun compareNode(
    path: List<String>,
    expected: Node,
    actual: Node,
    allowUnexpectedKeys: Boolean,
    matchers: MatchingRules
  ): List<BodyMismatch> {
    val nodePath = path + expected.nodeName
    val mismatches = when {
      Matchers.matcherDefined("body", nodePath, matchers) -> {
        logger.debug { "compareNode: Matcher defined for path $nodePath" }
        Matchers.domatch(matchers, "body", nodePath, expected, actual, BodyMismatchFactory)
      }
      actual.nodeName != expected.nodeName -> listOf(BodyMismatch(expected, actual,
        "Expected element ${expected.nodeName} but received ${actual.nodeName}",
        nodePath.joinToString(".")))
      else -> emptyList()
    }

    return if (mismatches.isEmpty()) {
      compareAttributes(nodePath, expected, actual, allowUnexpectedKeys, matchers) +
        compareChildren(nodePath, expected, actual, allowUnexpectedKeys, matchers) +
        compareText(nodePath, expected, actual, matchers)
    } else {
      mismatches
    }
  }

  private fun compareChildren(
    path: List<String>,
    expected: Node,
    actual: Node,
    allowUnexpectedKeys: Boolean,
    matchers: MatchingRules
  ): List<BodyMismatch> {
    var expectedChildren = asList(expected.childNodes).filter { n -> n.nodeType == ELEMENT_NODE }
    val actualChildren = asList(actual.childNodes).filter { n -> n.nodeType == ELEMENT_NODE }
    val mismatches = if (Matchers.matcherDefined("body", path, matchers)) {
      if (expectedChildren.isNotEmpty()) expectedChildren = expectedChildren.padTo(actualChildren.size, expectedChildren.first())
      emptyList()
    } else if (expectedChildren.isEmpty() && actualChildren.isNotEmpty() && !allowUnexpectedKeys) {
        listOf(BodyMismatch(expected, actual,
          "Expected an empty List but received ${actualChildren.size} child nodes",
          path.joinToString(".")))
    } else {
      emptyList()
    }

    val actualChildrenByTag = actualChildren.groupBy { it.nodeName }
    return mismatches + expectedChildren
      .groupBy { it.nodeName }
      .flatMap { e ->
        if (actualChildrenByTag.contains(e.key)) {
          e.value.zipAll(actualChildrenByTag.getValue(e.key)).mapIndexed { index, comp ->
            val expectedNode = comp.first
            val actualNode = comp.second
            when {
              expectedNode == null -> emptyList()
              actualNode == null -> listOf(BodyMismatch(expected, actual,
                "Expected child ${describe(expectedNode)} but was missing",
                (path + expectedNode.nodeName + index.toString()).joinToString(".")))
              else -> compareNode(path, expectedNode, actualNode, allowUnexpectedKeys, matchers)
            }
          }.flatten()
        } else {
          listOf(BodyMismatch(expected, actual,
            "Expected child <${e.key}/> but was missing", path.joinToString(".")))
        }
      }
  }

  private fun describe(node: Node) =
    if (node.nodeType == ELEMENT_NODE) {
      "<${node.nodeName}/>"
    } else {
      node.toString()
    }

  private fun compareAttributes(
    path: List<String>,
    expected: Node,
    actual: Node,
    allowUnexpectedKeys: Boolean,
    matchers: MatchingRules
  ): List<BodyMismatch> {
    val expectedAttrs = attributesToMap(expected.attributes)
    val actualAttrs = attributesToMap(actual.attributes)

    return if (expectedAttrs.isEmpty() && actualAttrs.isNotEmpty() && !allowUnexpectedKeys) {
      listOf(BodyMismatch(expected, actual,
        "Expected a Tag with at least ${expectedAttrs.size} attributes but received ${actual.attributes.length} attributes",
        path.joinToString(".")))
    } else {
      val mismatches = if (expectedAttrs.size > actualAttrs.size) {
        listOf(BodyMismatch(expected, actual, "Expected a Tag with at least ${expected.attributes.length} attributes but received ${actual.attributes.length} attributes",
          path.joinToString(".")))
      } else if (!allowUnexpectedKeys && expectedAttrs.size != actualAttrs.size) {
        listOf(BodyMismatch(expected, actual, "Expected a Tag with ${expected.attributes.length} attributes but received ${actual.attributes.length} attributes",
          path.joinToString(".")))
      } else {
        emptyList()
      }

      mismatches + expectedAttrs.flatMap { attr ->
        if (actualAttrs.contains(attr.key)) {
          val attrPath = appendAttribute(path, attr.key)
          val actualVal = actualAttrs[attr.key]
          when {
            Matchers.matcherDefined("body", attrPath, matchers) -> {
              logger.debug { "compareText: Matcher defined for path $attrPath" }
              Matchers.domatch(matchers, "body", attrPath, attr.value, actualVal, BodyMismatchFactory)
            }
            attr.value != actualVal ->
              listOf(BodyMismatch(expected, actual, "Expected ${attr.key}='${attr.value}' but received $actualVal",
                attrPath.joinToString(".")))
            else -> emptyList()
          }
        } else {
          listOf(BodyMismatch(expected, actual, "Expected ${attr.key}='${attr.value}' but was missing",
            appendAttribute(path, attr.key).joinToString(".")))
        }
      }
    }
  }

  private fun attributesToMap(attributes: NamedNodeMap?): Map<String, String> {
    return if (attributes == null) {
      emptyMap()
    } else {
      val map = mutableMapOf<String, String>()
      for (i in 0 until attributes.length) {
        val item = attributes.item(i)
        map[item.nodeName] = item.nodeValue
      }
      map
    }
  }
}
