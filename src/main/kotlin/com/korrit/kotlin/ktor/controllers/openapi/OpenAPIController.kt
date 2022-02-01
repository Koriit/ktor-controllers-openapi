package com.korrit.kotlin.ktor.controllers.openapi

import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get

/**
 * Serves provided OpenAPI specification under GET /openapi.
 */
fun Route.openAPIController(apiDoc: String) {
    get("/openapi") {
        // YAML doesn't have official content type
        // Text/Plain will also make browsers display it instead of download
        call.respondText(apiDoc, contentType = ContentType.Text.Plain)
    }
}

/**
 * Serves provided OpenAPI specification under GET /openapi.
 *
 * Also replaces following placeholders:
 *  * {{info.version}}
 *  * {{scheme}}
 *  * {{host}}
 *  * {{basePath}}
 */
fun Route.openAPIController(
    openapi: String,
    projectVersion: String,
    scheme: String,
    host: String,
    basePath: String
) {
    val apiDoc = openapi
        .replace("{{info.version}}", projectVersion)
        .replace("{{scheme}}", scheme)
        .replace("{{host}}", host)
        .replace("{{basePath}}", basePath)

    openAPIController(apiDoc)
}
