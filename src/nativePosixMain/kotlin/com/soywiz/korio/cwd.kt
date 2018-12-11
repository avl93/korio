package com.soywiz.korio

import kotlinx.cinterop.*
import platform.posix.*

fun doMkdir(path: String, attr: Int): Int {
	return platform.posix.mkdir(path, attr.convert())
}

fun realpath(path: String): String = memScoped {
	val temp = allocArray<ByteVar>(PATH_MAX)
	realpath(path, temp)
	temp.toKString()
}