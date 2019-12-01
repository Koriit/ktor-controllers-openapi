package korrit.kotlin.ktor.controllers.openapi.exceptions

/**
 * General analysis exception.
 */
class AnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
