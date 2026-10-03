package com.wardrobapp.api

import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {

    private val templates: List<String> =
        Routes::class.memberProperties.map { it.getter.call() as String }

    @Test
    fun `a value is encoded into the path, not spliced into it`() {
        // An id with a slash in it would otherwise address a different route
        // entirely -- `api/garments/x/in-use` from an id of "x/in-use".
        assertEquals("api/garments/x%2Fin-use", Routes.garment("x/in-use"))
        assertEquals("api/photos/a%20b.jpg", Routes.photo("a b.jpg"))
    }

    @Test
    fun `every route is relative`() {
        // Under Home Assistant's ingress the app is not at the root of the host,
        // and a leading slash would send every request past it.
        for (template in templates) {
            assertTrue(!template.startsWith("/"), "$template starts with a slash")
        }
    }

    @Test
    fun `no two routes are the same address`() {
        assertEquals(templates.size, templates.toSet().size, templates.groupBy { it }.filter { it.value.size > 1 }.keys.toString())
    }
}
