/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.smithy.generators

import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.rust.codegen.lang.RustWriter

class UnionGenerator(
    val model: Model,
    private val symbolProvider: SymbolProvider,
    private val writer: RustWriter,
    private val shape: UnionShape
) {

    fun render() {
        renderUnion()
    }

    private val sortedMembers: List<MemberShape> = shape.allMembers.values.sortedBy { symbolProvider.toMemberName(it) }
    private fun renderUnion() {
        val symbol = symbolProvider.toSymbol(shape)
        writer.write("#[derive(Debug, PartialEq, Clone)]")
        val blockWriter = writer.openBlock("pub enum ${symbol.name} {")
        sortedMembers.forEach { member ->
            blockWriter.write("${member.memberName.toPascalCase()}(\$T),", symbolProvider.toSymbol(member)) }
        blockWriter.closeBlock("}")
    }
}