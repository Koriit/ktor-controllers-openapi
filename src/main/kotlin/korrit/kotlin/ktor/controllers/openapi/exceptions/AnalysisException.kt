package korrit.kotlin.ktor.controllers.openapi.exceptions

/**
 * General analysis exception.
 */
open class AnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
