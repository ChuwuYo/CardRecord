package com.shuaji.cards.resources

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringResourcesConsistencyTest {
    @Test
    fun `Chinese and English strings keep the same keys and format arguments`() {
        val resources = locateResourcesDirectory()
        val chinese = readStrings(File(resources, "values/strings.xml"))
        val english = readStrings(File(resources, "values-en/strings.xml"))

        assertEquals("中英文 string key 必须完全一致", chinese.keys, english.keys)
        chinese.keys.forEach { key ->
            assertEquals(
                "$key 的格式化参数必须在中英文中保持一致",
                placeholders(chinese.getValue(key)),
                placeholders(english.getValue(key)),
            )
        }
    }

    @Test
    fun `Chinese and English plurals keep the same keys quantities and format arguments`() {
        val resources = locateResourcesDirectory()
        val chinese = readPlurals(File(resources, "values/strings.xml"))
        val english = readPlurals(File(resources, "values-en/strings.xml"))

        assertEquals("中英文 plurals key 必须完全一致", chinese.keys, english.keys)
        chinese.forEach { (key, chineseItems) ->
            val englishItems = english.getValue(key)
            assertEquals("$key 的 quantity 必须在中英文中保持一致", chineseItems.keys, englishItems.keys)
            chineseItems.forEach { (quantity, chineseValue) ->
                assertEquals(
                    "$key[$quantity] 的格式化参数必须在中英文中保持一致",
                    placeholders(chineseValue),
                    placeholders(englishItems.getValue(quantity)),
                )
            }
        }
    }

    private fun locateResourcesDirectory(): File {
        val moduleRelative = File("src/main/res")
        return if (moduleRelative.isDirectory) moduleRelative else File("app/src/main/res")
    }

    private fun readStrings(file: File): Map<String, String> {
        val nodes = parse(file).getElementsByTagName("string")
        return buildMap {
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") != "false") {
                    put(element.getAttribute("name"), element.textContent)
                }
            }
        }.toSortedMap()
    }

    private fun readPlurals(file: File): Map<String, Map<String, String>> {
        val nodes = parse(file).getElementsByTagName("plurals")
        return buildMap {
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                val items = element.getElementsByTagName("item")
                put(
                    element.getAttribute("name"),
                    buildMap {
                        repeat(items.length) { itemIndex ->
                            val item = items.item(itemIndex) as Element
                            put(item.getAttribute("quantity"), item.textContent)
                        }
                    }.toSortedMap(),
                )
            }
        }.toSortedMap()
    }

    private fun parse(file: File): Document {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                // Android 编译桩未暴露 Java 7 的 XMLConstants 静态字段，使用其标准 URI 值。
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun placeholders(value: String): List<String> = PLACEHOLDER.findAll(value.replace("%%", "")).map { it.value }.toList()

    private companion object {
        val PLACEHOLDER = Regex("%(?!%)(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z]")
    }
}
