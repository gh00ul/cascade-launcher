package com.gh00ul.cascade.data

/** A server answered with HTTP [code] instead of 200: it was reached, so the connection isn't to blame. */
class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")
