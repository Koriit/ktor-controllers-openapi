package korrit.kotlin.ktor.controllers.openapi

import com.korrit.kotlin.ktor.controllers.EmptyBodyInput
import com.korrit.kotlin.ktor.controllers.GET
import com.korrit.kotlin.ktor.controllers.HttpHeader
import com.korrit.kotlin.ktor.controllers.Input
import com.korrit.kotlin.ktor.controllers.PATCH
import com.korrit.kotlin.ktor.controllers.POST
import com.korrit.kotlin.ktor.controllers.PUT
import com.korrit.kotlin.ktor.controllers.errors
import com.korrit.kotlin.ktor.controllers.patch.PatchOf
import com.korrit.kotlin.ktor.controllers.path
import com.korrit.kotlin.ktor.controllers.query
import com.korrit.kotlin.ktor.controllers.responds
import com.korrit.kotlin.openapi.OpenAPIMatcher
import com.korrit.kotlin.openapi.OpenAPIReader
import com.korrit.kotlin.openapi.model.OpenAPI
import io.ktor.application.ApplicationCall
import io.ktor.http.HttpHeaders.Location
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.routing.Routing
import io.ktor.routing.routing
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.util.pipeline.PipelineContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

internal class KtorOpenAPIAnalyzerTest {

    @Suppress("LongMethod")
    @Test
    fun testAnalysis() {
        data class Entity(
            val id: Long,
            val code: String
        )

        class EntityPatch : PatchOf<Entity>() {
            var id by patchOf(Entity::id, required = true)
            var code by patchOf(Entity::code)
        }

        data class EntitiesList(
            val entities: List<Entity>
        )

        data class Case(
            val expectedDoc: String,
            val route: Routing.() -> Unit
        )

        mapOf(
            "query params with defaults" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /:
                    get:  
                      parameters:
                        - name: limit
                          in: query
                          schema:
                  
                            type: integer
                            format: int32
                            default: 0
                        - name: page
                          in: query
                          schema:
                            type: integer
                            format: int32
                            default: 0
                      responses:
                        "200":  
                          description: OK
                          content:
                            application/json:  
                              schema:
                                title: EntitiesList
                                type: object
                                required: [entities]
                                properties:
                                  entities:
                                    title: EntityList
                                    type: array
                                    items:
                                      title: Entity
                                      type: object
                                      required: [id, code]
                                      properties:
                                        code:
                                          type: string
                                        id:
                                          type: integer
                                          format: int64
                """.trimIndent(),
                // </editor-fold>
                route = {
                    GET("/") {
                        object : EmptyBodyInput() {
                            val limit: Int by query(default = 0)
                            val page: Int by query(default = 0)

                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<EntitiesList>(OK)
                }
            ),
            "path param" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /{id}:
                    get:  
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                            format: int64
                      responses:
                        "200":  
                          description: OK
                          content:
                            application/json:  
                              schema:
                                title: Entity
                                type: object
                                required: [id, code]
                                properties:
                                  code:
                                    type: string
                                  id:
                                    type: integer
                                    format: int64
                        "404":  
                          description: Not Found
                """.trimIndent(),
                // </editor-fold>
                route = {
                    GET("/{id}") {
                        object : EmptyBodyInput() {
                            val id: Long by path()

                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<Entity>(OK)
                        .errors(HttpStatusCode.NotFound)
                }
            ),
            "path param name override" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /code={entityCode}:
                    get:  
                      parameters:
                        - name: entityCode
                          in: path
                          required: true
                          schema:  
                            type: string
                      responses:
                        "200":  
                          description: OK
                          content:
                            application/json:  
                              schema:  
                                title: Entity
                                type: object
                                required: [id, code]
                                properties:
                                  code:  
                                    type: string
                                  id:  
                                    type: integer
                                    format: int64
                        "404":  
                          description: Not Found
                """.trimIndent(),
                // </editor-fold>
                route = {
                    GET("/code={entityCode}") {
                        object : EmptyBodyInput() {
                            val code: String by path(name = "entityCode")

                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<Entity>(OK)
                        .errors(HttpStatusCode.NotFound)
                }
            ),
            "response with custom headers" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:  
                            schema:  
                              title: Entity
                              type: object
                              required: [id, code]
                              properties:
                                code:  
                                  type: string
                                id:  
                                  type: integer
                                  format: int64
                      responses:
                        "201":  
                          description: Created
                          headers:  
                            Location:  
                              required: true
                              schema:  
                                type: string
                        "400":  
                          description: Bad Request
                """.trimIndent(),
                // </editor-fold>
                route = {
                    POST("/") {
                        object : Input<Entity>() {
                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<Unit>(Created, headers = listOf(HttpHeader(Location)))
                        .errors(BadRequest)
                }
            ),
            "response with empty body" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /{id}:
                    put:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:  
                            type: integer
                            format: int64
                      requestBody:
                        required: true
                        content:
                          application/json:  
                            schema:  
                              title: Entity
                              type: object
                              required: [id, code]
                              properties:
                                code:  
                                  type: string
                                id:  
                                  type: integer
                                  format: int64
                      responses:
                        "200":  
                          description: OK
                        "400":  
                          description: Bad Request
                """.trimIndent(),
                // </editor-fold>
                route = {
                    PUT("/{id}") {
                        object : Input<Entity>() {
                            val id: Long by path()

                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<Unit>(OK)
                        .errors(BadRequest)
                }
            ),
            "request with patch body" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /{id}:
                    put:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:  
                            type: integer
                            format: int64
                      requestBody:
                        required: true
                        content:
                          application/json:  
                            schema:  
                              title: EntityPatch
                              type: object
                              required: [id, code]
                              properties:
                                code:  
                                  type: string
                                id:  
                                  type: integer
                                  format: int64
                      responses:
                        "200":  
                          description: OK
                        "400":  
                          description: Bad Request
                """.trimIndent(),
                // </editor-fold>
                route = {
                    PUT("/{id}") {
                        object : Input<EntityPatch>() {
                            val id: Long by path()

                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<Unit>(OK)
                        .errors(BadRequest)
                }
            ),
            "PATCH request with patch body" to Case(
                // <editor-fold defaultstate="collapsed" desc="expectedDoc = ...">
                expectedDoc = """  
                  /{id}:
                    patch:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:  
                            type: integer
                            format: int64
                      requestBody:
                        required: true
                        content:
                          application/json:  
                            schema:  
                              title: EntityPatch
                              type: object
                              required: [id]
                              properties:
                                code:  
                                  type: string
                                id:  
                                  type: integer
                                  format: int64
                      responses:
                        "200":  
                          description: OK
                        "400":  
                          description: Bad Request
                """.trimIndent(),
                // </editor-fold>
                route = {
                    PATCH("/{id}") {
                        object : Input<EntityPatch>() {
                            val id: Long by path()

                            override suspend fun PipelineContext<Unit, ApplicationCall>.respond() = Unit
                        }
                    }
                        .responds<Unit>(OK)
                        .errors(BadRequest)
                }
            )
        ).testCases {
            val server = TestApplicationEngine(
                applicationEngineEnvironment {
                    module {
                        routing(route)
                    }
                }
            )
            server.start()
            val analyzer = KtorOpenAPIAnalyzer(
                ktor = server.application
            )
            val source: OpenAPI = analyzer.analyze()

            try {
                val doc: OpenAPI = OpenAPIReader().load(
                    """
                    |openapi: 3.0.2
                    |paths:
                    |${expectedDoc.prependIndent("  ")}
                    """.trimMargin().byteInputStream()
                )

                val errors = OpenAPIMatcher().match(doc, source)

                server.stop(0L, 0L)

                if (errors.isNotEmpty()) {
                    errors.forEach { println(it) }
                    fail("There are ${errors.size} validation errors!")
                }
            } catch (e: Throwable) {
                println("Result of server analysis:\n$source")
                throw e
            }
        }
    }
}
