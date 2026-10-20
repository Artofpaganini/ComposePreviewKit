package com.previewkit.ksp.resolve

import com.google.devtools.ksp.symbol.KSClassDeclaration

internal fun findConcreteSubclasses(sealed: KSClassDeclaration): List<KSClassDeclaration> {
    return sealed.getSealedSubclasses().flatMap { subclass ->
        val nested = subclass.getSealedSubclasses().toList()
        if (nested.isNotEmpty()) findConcreteSubclasses(subclass) else listOf(subclass)
    }.toList()
}
