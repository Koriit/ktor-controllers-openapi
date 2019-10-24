package korrit.kotlin.ktor.controllers.openapi

import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import java.io.InputStreamReader

fun Route.openApiController(
        openapi: InputStreamReader,
        projectVersion: String,
        scheme: String,
        host: String,
        basePath: String
) {
    val apiDoc = openapi.readText()
            .replace("{{info.version}}", projectVersion)
            .replace("{{host}}", host)
            .replace("{{basePath}}", basePath)
            .replace("{{scheme}}", scheme)

    get("/openapi") {
        call.respondText(apiDoc, contentType = ContentType.Text.Plain)
    }
}