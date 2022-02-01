package korrit.kotlin.ktor.controllers.openapi

import io.ktor.application.call
import io.ktor.http.content.defaultResource
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.response.respondRedirect
import io.ktor.routing.Route
import io.ktor.routing.application
import io.ktor.routing.get
import io.ktor.server.engine.ApplicationEngineEnvironment

/**
 * Serves Swagger UI under GET /swagger-ui.
 *
 * Also redirects /swagger-ui.html to /swagger-ui
 */
fun Route.swaggerUIController() {
    with(application.environment) {
        if (this is ApplicationEngineEnvironment) {
            val basePath = if (rootPath != "/") rootPath else ""
            connectors.forEach {
                log.info("Serving SwaggerUI at ${it.type.name.lowercase()}://${it.host}:${it.port}$basePath/swagger-ui")
            }
        }
    }

    get("/swagger-ui.html") {
        call.respondRedirect("swagger-ui")
    }

    static("/swagger-ui") {
        resources("swagger-ui")
        defaultResource("/swagger-ui/index.html")
    }
}
