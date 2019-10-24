package korrit.kotlin.ktor.controllers.openapi

import io.ktor.application.call
import io.ktor.http.content.defaultResource
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.response.respondRedirect
import io.ktor.routing.Route
import io.ktor.routing.get

fun Route.swaggerUIController() {
    get("/swagger-ui.html") {
        call.respondRedirect("swagger-ui")
    }

    static("/swagger-ui") {
        resources("swagger-ui")
        defaultResource("/swagger-ui/index.html")
    }
}