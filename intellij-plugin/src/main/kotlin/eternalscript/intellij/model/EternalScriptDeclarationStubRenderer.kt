package eternalscript.intellij.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.SmartPointerManager
import eternalscript.intellij.resolve.Idea262Facade
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.name.render

internal data class EternalScriptRenderedCallable(
    val text: String,
    val exposedType: String?,
    val kind: EternalScriptDeclarationKind,
    val receiverType: String?,
    val parameterTypes: List<String>
)

internal data class EternalScriptClassifierStub(
    val text: String,
    val mappings: List<EternalScriptGeneratedMapping>
)

internal object EternalScriptDeclarationStubRenderer {
    fun callable(
        declaration: KtCallableDeclaration,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): EternalScriptRenderedCallable? {
        ProgressManager.checkCanceled()
        return when (declaration) {
            is KtProperty -> renderProperty(declaration, availableDeclarations)
            is KtNamedFunction -> renderFunction(declaration, availableDeclarations)
            else -> null
        }
    }

    fun classifier(
        sourceUrl: String,
        declaration: KtNamedDeclaration,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>> = emptyMap()
    ): EternalScriptClassifierStub? {
        ProgressManager.checkCanceled()
        val builder = ClassifierStubBuilder(sourceUrl, availableDeclarations)
        if (!builder.render(declaration)) return null
        return EternalScriptClassifierStub(builder.text(), builder.mappings())
    }

    private fun renderProperty(
        property: KtProperty,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): EternalScriptRenderedCallable? {
        ProgressManager.checkCanceled()
        val name = property.nameIdentifier?.text ?: return null
        val explicitType = property.typeReference?.let(::renderType)
        if (property.typeReference != null && explicitType == null) return null
        val receiverType = property.receiverTypeReference?.let(::renderType)
        if (property.receiverTypeReference != null && receiverType == null) return null
        val type = explicitType ?: cheapExpressionType(property.initializer)
        ?: inferType(property, availableDeclarations)
        ?: renderSemanticReturnType(property)
        ?: return null
        val annotations = renderDeclarationAnnotations(property, availableDeclarations) ?: return null
        val getterAnnotations = property.getter?.let { accessor ->
            renderDeclarationAnnotations(accessor, availableDeclarations) ?: return null
        }.orEmpty()
        val setterAnnotations = property.setter?.let { accessor ->
            renderDeclarationAnnotations(accessor, availableDeclarations) ?: return null
        }.orEmpty()
        val setterParameterAnnotations = property.setter?.parameter?.let { parameter ->
            renderDeclarationAnnotations(parameter, availableDeclarations) ?: return null
        }.orEmpty()
        val containingClassifier = property.getStrictParentOfType<KtClassOrObject>()
        val bodylessContract = property.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
            property.hasModifier(KtTokens.LATEINIT_KEYWORD) ||
            property.hasModifier(KtTokens.EXTERNAL_KEYWORD) ||
            ((containingClassifier as? KtClass)?.isInterface() == true &&
                property.initializer == null &&
                !property.hasDelegate() &&
                property.accessors.none { accessor -> accessor.bodyExpression != null })
        val canHaveBackingField = receiverType == null &&
            (containingClassifier as? KtClass)?.isInterface() != true &&
            !property.hasDelegate()
        val renderGetter = getterAnnotations.isNotEmpty() || property.getter.hasExplicitVisibility()
        val renderSetter = setterAnnotations.isNotEmpty() || setterParameterAnnotations.isNotEmpty() ||
            property.setter.hasExplicitVisibility()
        val text = buildString {
            if (annotations.isNotEmpty()) append(annotations).append('\n')
            appendPropertyModifiers(property)
            append(if (property.isVar) "var " else "val ")
            receiverType?.let { receiver -> append(receiver).append('.') }
            append(name).append(": ").append(type)
            when {
                property.hasModifier(KtTokens.CONST_KEYWORD) -> {
                    val initializer = property.initializer?.let(::renderAnnotationDefault) ?: return null
                    append(" = ").append(initializer)
                }
                property.hasDelegate() -> appendSyntheticDelegate(
                    type,
                    property.isVar,
                    receiverType ?: if (containingClassifier == null) "kotlin.Nothing?" else "kotlin.Any?"
                )
                bodylessContract -> {
                    if (renderGetter) {
                        appendBodylessGetter(property.getter, getterAnnotations)
                    }
                    if (property.isVar && renderSetter) {
                        appendBodylessSetter(property.setter, setterAnnotations)
                    }
                }
                canHaveBackingField -> {
                    append(" = kotlin.error(\"EternalScript IDE declaration\")")
                    if (renderGetter) {
                        append("\n    ")
                        if (getterAnnotations.isNotEmpty()) append(getterAnnotations).append(' ')
                        appendAccessorVisibility(property.getter)
                        append("get() = field")
                    }
                    if (property.isVar && renderSetter) {
                        append("\n    ")
                        if (setterAnnotations.isNotEmpty()) append(setterAnnotations).append(' ')
                        appendAccessorVisibility(property.setter)
                        append("set(")
                        if (setterParameterAnnotations.isNotEmpty()) {
                            append(setterParameterAnnotations).append(' ')
                        }
                        append("value) { field = value }")
                    }
                }
                else -> {
                    append("\n    ")
                    if (getterAnnotations.isNotEmpty()) append(getterAnnotations).append(' ')
                    appendAccessorVisibility(property.getter)
                    append("get() = kotlin.error(\"EternalScript IDE declaration\")")
                    if (property.isVar) {
                        append("\n    ")
                        if (setterAnnotations.isNotEmpty()) append(setterAnnotations).append(' ')
                        appendAccessorVisibility(property.setter)
                        append("set(")
                        if (setterParameterAnnotations.isNotEmpty()) {
                            append(setterParameterAnnotations).append(' ')
                        }
                        append("value) { kotlin.error(\"EternalScript IDE declaration\") }")
                    }
                }
            }
        }
        return EternalScriptRenderedCallable(
            text,
            type,
            EternalScriptDeclarationKind.PROPERTY,
            receiverType,
            emptyList()
        )
    }

    private fun renderFunction(
        function: KtNamedFunction,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): EternalScriptRenderedCallable? {
        ProgressManager.checkCanceled()
        val name = function.nameIdentifier?.text ?: return null
        val explicitReturnType = function.typeReference?.let(::renderType)
        if (function.typeReference != null && explicitReturnType == null) return null
        val receiverType = function.receiverTypeReference?.let(::renderType)
        if (function.receiverTypeReference != null && receiverType == null) return null
        val parameters = function.valueParameters.map { parameter ->
            renderParameter(parameter, availableDeclarations = availableDeclarations) ?: return null
        }
        val parameterTypes = function.valueParameters.map { parameter ->
            parameter.typeReference?.let(::renderType) ?: return null
        }
        val typeParameters = function.typeParameterList?.let(::renderWithQualifiedTypes)
        if (function.typeParameterList != null && typeParameters == null) return null
        val constraints = function.typeConstraintList?.let(::renderWithQualifiedTypes)
        if (function.typeConstraintList != null && constraints == null) return null
        val returnType = explicitReturnType ?: (if (function.hasBlockBody()) "kotlin.Unit" else null)
        ?: cheapExpressionType(function.bodyExpression)
        ?: function.bodyExpression?.let { expression -> inferExpressionType(expression, availableDeclarations) }
        ?: renderSemanticReturnType(function)
        ?: return null
        val annotations = renderDeclarationAnnotations(function, availableDeclarations) ?: return null
        val text = buildString {
            if (annotations.isNotEmpty()) append(annotations).append('\n')
            appendFunctionModifiers(function)
            append("fun ")
            typeParameters?.let { rendered -> append(rendered).append(' ') }
            receiverType?.let { receiver -> append(receiver).append('.') }
            append(name)
            append('(')
            append(parameters.joinToString(", "))
            append(')')
            append(": ").append(returnType)
            constraints?.let { rendered -> append(" where ").append(rendered) }
            if (function.bodyExpression != null) {
                append(" = kotlin.error(\"EternalScript IDE declaration\")")
            }
        }
        return EternalScriptRenderedCallable(
            text,
            returnType,
            EternalScriptDeclarationKind.FUNCTION,
            receiverType,
            parameterTypes
        )
    }

    private fun renderParameter(
        parameter: KtParameter,
        preserveDefaultValue: Boolean = false,
        constructorProperty: Boolean = false,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>> = emptyMap()
    ): String? {
        val type = parameter.typeReference?.let(::renderType) ?: return null
        val defaultValue = if (!parameter.hasDefaultValue()) {
            null
        } else if (preserveDefaultValue) {
            parameter.defaultValue?.let(::renderAnnotationDefault) ?: return null
        } else {
            "kotlin.error(\"EternalScript IDE default\")"
        }
        val annotations = renderDeclarationAnnotations(parameter, availableDeclarations) ?: return null
        return buildString {
            if (annotations.isNotEmpty()) append(annotations).append(' ')
            if (constructorProperty) {
                appendModifiers(
                    parameter,
                    KtTokens.PUBLIC_KEYWORD,
                    KtTokens.PROTECTED_KEYWORD,
                    KtTokens.INTERNAL_KEYWORD,
                    KtTokens.PRIVATE_KEYWORD,
                    KtTokens.FINAL_KEYWORD,
                    KtTokens.OPEN_KEYWORD,
                    KtTokens.OVERRIDE_KEYWORD
                )
            }
            if (parameter.hasModifier(KtTokens.CROSSINLINE_KEYWORD)) append("crossinline ")
            if (parameter.hasModifier(KtTokens.NOINLINE_KEYWORD)) append("noinline ")
            if (parameter.isVarArg) append("vararg ")
            if (constructorProperty && parameter.hasValOrVar()) {
                append(parameter.valOrVarKeyword?.text).append(' ')
            }
            append(parameter.nameIdentifier?.text ?: "value")
            append(": ").append(type)
            defaultValue?.let { rendered -> append(" = ").append(rendered) }
        }
    }

    private class ClassifierStubBuilder(
        private val sourceUrl: String,
        private val availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ) {
        private val output = StringBuilder()
        private val mappings = mutableListOf<EternalScriptGeneratedMapping>()

        fun text(): String = output.toString()
        fun mappings(): List<EternalScriptGeneratedMapping> = mappings.toList()

        fun render(declaration: KtNamedDeclaration): Boolean {
            ProgressManager.checkCanceled()
            val start = output.length
            val mappingStart = mappings.size
            declaration.docComment?.text?.let { documentation -> output.append(documentation).append('\n') }
            if (declaration !is KtProperty && declaration !is KtNamedFunction) {
                val annotations = renderDeclarationAnnotations(declaration, availableDeclarations) ?: return false
                if (annotations.isNotEmpty()) output.append(annotations).append('\n')
            }
            val rendered = when (declaration) {
                is KtTypeAlias -> renderTypeAlias(declaration)
                is KtClassOrObject -> renderClassOrObject(declaration)
                is KtSecondaryConstructor -> renderSecondaryConstructor(declaration)
                is KtCallableDeclaration -> callable(declaration, availableDeclarations)?.let { callable ->
                    output.append(callable.text)
                    true
                } ?: false
                else -> false
            }
            if (!rendered) {
                output.setLength(start)
                if (mappings.size > mappingStart) mappings.subList(mappingStart, mappings.size).clear()
                return false
            }
            mappings += EternalScriptGeneratedMapping(
                TextRange(start, output.length),
                EternalScriptSourceLocation(sourceUrl, declaration.textOffset),
                SmartPointerManager.createPointer(declaration)
            )
            return true
        }

        private fun renderTypeAlias(alias: KtTypeAlias): Boolean {
            val typeParameters = alias.typeParameterList?.let(::renderWithQualifiedTypes)
            if (alias.typeParameterList != null && typeParameters == null) return false
            val expandedType = alias.getTypeReference()?.let(::renderType) ?: return false
            if (alias.hasModifier(KtTokens.INTERNAL_KEYWORD)) output.append("internal ")
            output.append("typealias ").append(alias.nameIdentifier?.text ?: return false)
            typeParameters?.let(output::append)
            output.append(" = ").append(expandedType)
            return true
        }

        private fun renderClassOrObject(declaration: KtClassOrObject): Boolean {
            val typeParameters = declaration.typeParameterList?.let(::renderWithQualifiedTypes)
            if (declaration.typeParameterList != null && typeParameters == null) return false
            val constraints = declaration.typeConstraintList?.let(::renderWithQualifiedTypes)
            if (declaration.typeConstraintList != null && constraints == null) return false
            val supertypes = declaration.superTypeListEntries.map { entry ->
                renderSupertype(entry) ?: return false
            }
            appendClassModifiers(declaration)
            val keyword = when {
                declaration is KtObjectDeclaration -> "object"
                declaration is KtClass && declaration.isInterface() -> "interface"
                declaration is KtClass && declaration.isEnum() -> "enum class"
                declaration is KtClass && declaration.isAnnotation() -> "annotation class"
                else -> "class"
            }
            output.append(keyword)
            val name = declaration.nameIdentifier?.text
            if (name != null) {
                output.append(' ').append(name)
            } else if (declaration !is KtObjectDeclaration || !declaration.isCompanion()) {
                return false
            }
            typeParameters?.let(output::append)
            if (declaration is KtClass && !declaration.isInterface()) {
                val constructor = declaration.primaryConstructor
                if (constructor != null) {
                    val annotations = renderDeclarationAnnotations(constructor, availableDeclarations) ?: return false
                    val explicitConstructorKeyword = constructor.hasConstructorKeyword() ||
                        VISIBILITY_MODIFIERS.any(constructor::hasModifier) || annotations.isNotEmpty()
                    if (explicitConstructorKeyword) {
                        output.append(' ')
                        if (annotations.isNotEmpty()) output.append(annotations).append(' ')
                        output.appendVisibility(constructor)
                        output.append("constructor")
                    }
                    output.append('(')
                    constructor.valueParameters.forEachIndexed { index, parameter ->
                        if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                        if (index > 0) output.append(", ")
                        val start = output.length
                        val renderedParameter = renderParameter(
                            parameter,
                            preserveDefaultValue = declaration.isAnnotation(),
                            constructorProperty = true,
                            availableDeclarations = availableDeclarations
                        ) ?: return false
                        output.append(renderedParameter)
                        mappings += EternalScriptGeneratedMapping(
                            TextRange(start, output.length),
                            EternalScriptSourceLocation(sourceUrl, parameter.textOffset),
                            SmartPointerManager.createPointer(parameter)
                        )
                    }
                    output.append(')')
                }
            }
            if (supertypes.isNotEmpty()) output.append(" : ").append(supertypes.joinToString(", "))
            constraints?.let { rendered -> output.append(" where ").append(rendered) }
            val members = declaration.declarations.filter { member ->
                member is KtSecondaryConstructor || isShared(member)
            }
            val entries = (declaration as? KtClass)?.declarations?.filterIsInstance<KtEnumEntry>().orEmpty()
            if (members.isEmpty() && entries.isEmpty()) return true
            output.append(" {\n")
            if (entries.isNotEmpty()) {
                entries.forEachIndexed { index, entry ->
                    if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                    output.append("    ")
                    val start = output.length
                    val annotations = renderDeclarationAnnotations(entry, availableDeclarations) ?: return false
                    if (annotations.isNotEmpty()) output.append(annotations).append('\n')
                    output.append(entry.nameIdentifier?.text ?: "ENTRY")
                    val arguments = entry.initializerList?.initializers
                        ?.filterIsInstance<KtSuperTypeCallEntry>()
                        ?.flatMap { call -> call.valueArguments }
                        .orEmpty()
                    if (arguments.isNotEmpty()) {
                        val parameters = declaration.primaryConstructorParameters
                        val renderedArguments = arguments.map { argument ->
                            renderSyntheticArgument(argument as? KtValueArgument ?: return false, parameters)
                                ?: return false
                        }
                        output.append('(')
                        output.append(renderedArguments.joinToString(", "))
                        output.append(')')
                    }
                    val entryMembers = entry.declarations.filterIsInstance<KtNamedDeclaration>().filter(::isShared)
                    if (entryMembers.isNotEmpty()) {
                        output.append(" {\n")
                        entryMembers.forEachIndexed { memberIndex, member ->
                        if (memberIndex and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                        output.append("        ")
                        val before = output.length
                        if (!render(member)) return false
                            if (output.length > before) output.append('\n')
                        }
                        output.append("    }")
                    }
                    mappings += EternalScriptGeneratedMapping(
                        TextRange(start, output.length),
                        EternalScriptSourceLocation(sourceUrl, entry.textOffset),
                        SmartPointerManager.createPointer(entry)
                    )
                    output.append(if (index == entries.lastIndex) ";\n" else ",\n")
                }
            }
            members.filterNot { member -> member is KtEnumEntry }.forEachIndexed { index, member ->
                if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                val named = member as? KtNamedDeclaration ?: return@forEachIndexed
                output.append("    ")
                val before = output.length
                if (!render(named)) return false
                if (output.length > before) output.append('\n')
            }
            output.append('}')
            return true
        }

        private fun appendClassModifiers(declaration: KtClassOrObject) {
            listOf(
                KtTokens.PUBLIC_KEYWORD,
                KtTokens.PROTECTED_KEYWORD,
                KtTokens.INTERNAL_KEYWORD,
                KtTokens.COMPANION_KEYWORD,
                KtTokens.SEALED_KEYWORD,
                KtTokens.ABSTRACT_KEYWORD,
                KtTokens.OPEN_KEYWORD,
                KtTokens.DATA_KEYWORD,
                KtTokens.VALUE_KEYWORD,
                KtTokens.INNER_KEYWORD,
                KtTokens.FUN_KEYWORD
            ).forEach { token -> if (declaration.hasModifier(token)) output.append(token.value).append(' ') }
        }

        private fun renderSecondaryConstructor(constructor: KtSecondaryConstructor): Boolean {
            output.appendVisibility(constructor)
            output.append("constructor(")
            constructor.valueParameters.forEachIndexed { index, parameter ->
                if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                if (index > 0) output.append(", ")
                output.append(renderParameter(parameter, availableDeclarations = availableDeclarations) ?: return false)
            }
            output.append(')')
            val delegation = constructor.children.filterIsInstance<KtConstructorDelegationCall>()
                .singleOrNull()
                ?.takeUnless(KtConstructorDelegationCall::isImplicit)
            if (delegation != null) {
                output.append(" : ").append(if (delegation.isCallToThis) "this" else "super").append('(')
                delegation.valueArguments.forEachIndexed { index, argument ->
                    if (index > 0) output.append(", ")
                    val valueArgument = argument as? KtValueArgument ?: return false
                    output.append(
                        renderSyntheticArgument(valueArgument, constructor.valueParameters) ?: return false
                    )
                }
                output.append(')')
            }
            output.append(" {}")
            return true
        }

        private fun renderSupertype(entry: org.jetbrains.kotlin.psi.KtSuperTypeListEntry): String? {
            val type = entry.typeReference?.let(::renderType) ?: return null
            val parameters = entry.getStrictParentOfType<KtClass>()?.primaryConstructorParameters.orEmpty()
            return when (entry) {
                is KtSuperTypeCallEntry -> {
                    val arguments = entry.valueArguments.map { argument ->
                        renderSyntheticArgument(argument as? KtValueArgument ?: return null, parameters)
                            ?: return null
                    }
                    "$type(${arguments.joinToString(", ")})"
                }
                is KtDelegatedSuperTypeEntry -> {
                    val expression = entry.delegateExpression ?: return null
                    val rendered = renderSyntheticExpression(expression, parameters) ?: return null
                    "$type by $rendered"
                }
                else -> type
            }
        }
    }

    private fun StringBuilder.appendPropertyModifiers(property: KtProperty) {
        appendModifiers(
            property,
            KtTokens.PUBLIC_KEYWORD,
            KtTokens.PROTECTED_KEYWORD,
            KtTokens.INTERNAL_KEYWORD,
            KtTokens.FINAL_KEYWORD,
            KtTokens.OPEN_KEYWORD,
            KtTokens.ABSTRACT_KEYWORD,
            KtTokens.OVERRIDE_KEYWORD,
            KtTokens.CONST_KEYWORD,
            KtTokens.LATEINIT_KEYWORD,
            KtTokens.EXTERNAL_KEYWORD
        )
    }

    private fun StringBuilder.appendFunctionModifiers(function: KtNamedFunction) {
        appendModifiers(
            function,
            KtTokens.PUBLIC_KEYWORD,
            KtTokens.PROTECTED_KEYWORD,
            KtTokens.INTERNAL_KEYWORD,
            KtTokens.FINAL_KEYWORD,
            KtTokens.OPEN_KEYWORD,
            KtTokens.ABSTRACT_KEYWORD,
            KtTokens.OVERRIDE_KEYWORD,
            KtTokens.TAILREC_KEYWORD,
            KtTokens.INLINE_KEYWORD,
            KtTokens.SUSPEND_KEYWORD,
            KtTokens.OPERATOR_KEYWORD,
            KtTokens.INFIX_KEYWORD,
            KtTokens.EXTERNAL_KEYWORD
        )
    }

    private fun StringBuilder.appendAccessorVisibility(accessor: KtPropertyAccessor?) {
        if (accessor != null) appendVisibility(accessor)
    }

    private fun StringBuilder.appendBodylessGetter(
        accessor: KtPropertyAccessor?,
        annotations: String
    ) {
        append("\n    ")
        if (annotations.isNotEmpty()) append(annotations.replace('\n', ' ')).append(' ')
        appendAccessorVisibility(accessor)
        append("get")
    }

    private fun StringBuilder.appendBodylessSetter(
        accessor: KtPropertyAccessor?,
        annotations: String
    ) {
        append("\n    ")
        if (annotations.isNotEmpty()) append(annotations.replace('\n', ' ')).append(' ')
        appendAccessorVisibility(accessor)
        append("set")
    }

    private fun StringBuilder.appendSyntheticDelegate(
        type: String,
        mutable: Boolean,
        thisRefType: String
    ) {
        val contract = if (mutable) "ReadWriteProperty" else "ReadOnlyProperty"
        append(" by object : kotlin.properties.").append(contract)
            .append('<').append(thisRefType).append(", ").append(type).append("> {\n")
        append("    override operator fun getValue(thisRef: ").append(thisRefType)
            .append(", property: kotlin.reflect.KProperty<*>): ")
            .append(type).append(" = kotlin.error(\"EternalScript IDE declaration\")")
        if (mutable) {
            append("\n    override operator fun setValue(thisRef: ").append(thisRefType)
                .append(", property: kotlin.reflect.KProperty<*>, value: ")
                .append(type).append(") {}")
        }
        append("\n}")
    }

    private fun KtPropertyAccessor?.hasExplicitVisibility(): Boolean =
        this != null && VISIBILITY_MODIFIERS.any(this::hasModifier)

    private fun StringBuilder.appendVisibility(declaration: org.jetbrains.kotlin.psi.KtModifierListOwner) {
        appendModifiers(declaration, *VISIBILITY_MODIFIERS)
    }

    private fun StringBuilder.appendModifiers(
        declaration: org.jetbrains.kotlin.psi.KtModifierListOwner,
        vararg modifiers: org.jetbrains.kotlin.lexer.KtModifierKeywordToken
    ) {
        modifiers.forEach { modifier ->
            if (declaration.hasModifier(modifier)) append(modifier.value).append(' ')
        }
    }

    private fun referencedParameterType(
        parameters: List<KtParameter>,
        expression: KtExpression
    ): String? {
        val name = (expression as? KtNameReferenceExpression)?.getReferencedName()
            ?: return cheapExpressionType(expression)
        return parameters.firstOrNull { parameter -> parameter.name == name }
            ?.typeReference?.let(::renderType)
    }

    private fun renderSyntheticArgument(
        argument: KtValueArgument,
        parameters: List<KtParameter>
    ): String? {
        val expression = argument.getArgumentExpression() ?: return null
        val value = renderSyntheticExpression(expression, parameters) ?: return null
        return buildString {
            argument.getArgumentName()?.asName?.render()?.let { name -> append(name).append(" = ") }
            if (argument.isSpread) append('*')
            append(value)
        }
    }

    private fun renderSyntheticExpression(
        expression: KtExpression,
        parameters: List<KtParameter>
    ): String? {
        val type = referencedParameterType(parameters, expression)
            ?: if (canUseSemanticAnalysis(expression.containingKtFile)) {
                Idea262Facade.renderExpressionType(expression)
                    ?.let(Idea262Facade::localizeSyntheticWorkspaceTypes)
            } else {
                null
            }
            ?: return null
        return "(kotlin.error(\"EternalScript IDE constructor argument\") as $type)"
    }

    private fun renderType(reference: KtTypeReference): String? =
        renderSelfContainedType(reference) ?: if (canUseSemanticAnalysis(reference.containingKtFile)) {
            Idea262Facade.renderType(reference)
                ?.let(Idea262Facade::localizeSyntheticWorkspaceTypes)
        } else {
            null
        }

    private fun renderSemanticReturnType(declaration: KtCallableDeclaration): String? =
        if (canUseSemanticAnalysis(declaration.containingKtFile)) {
            Idea262Facade.renderReturnType(declaration)
                ?.let(Idea262Facade::localizeSyntheticWorkspaceTypes)
        } else {
            null
        }

    private fun renderAnnotationDefault(expression: KtExpression): String? {
        val text = expression.text
        val selfContained = when (expression) {
            is KtConstantExpression -> constantType(text) != null || text == "null"
            is KtStringTemplateExpression -> '$' !in text
            else -> false
        }
        if (selfContained) return text
        return if (canUseSemanticAnalysis(expression.containingKtFile)) {
            Idea262Facade.renderAnnotationDefault(expression)
        } else {
            null
        }
    }

    private fun renderDeclarationAnnotations(
        declaration: KtDeclaration,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>> = emptyMap()
    ): String? {
        if (declaration.annotationEntries.isEmpty()) return ""
        val annotations = ArrayList<String>(declaration.annotationEntries.size)
        declaration.annotationEntries.forEach { entry ->
            annotations += renderSyntacticAnnotation(entry, availableDeclarations) ?: return null
        }
        return annotations.joinToString("\n")
    }

    /**
     * PSI owns the original use-site target (`field`, `get`, `setparam`, and so on). K2 symbols
     * redistribute those annotations across accessors and backing fields, so render each source
     * entry here while delegating type and constant-value qualification to K2.
     */
    private fun renderSyntacticAnnotation(
        entry: KtAnnotationEntry,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): String? {
        val semanticAnalysisAvailable = canUseSemanticAnalysis(entry.containingKtFile)
        if (semanticAnalysisAvailable) {
            Idea262Facade.renderAnnotationEntry(entry)?.let { return it }
        }
        val type = if (semanticAnalysisAvailable) {
            renderTrustedSyntacticAnnotationType(entry, availableDeclarations)
        } else {
            entry.typeReference?.let(::renderType)
                ?: renderSharedAnnotationType(entry, availableDeclarations)
        } ?: return null
        val arguments = entry.valueArguments.map { argument ->
            val valueArgument = argument as? KtValueArgument ?: return null
            val expression = valueArgument.getArgumentExpression() ?: return null
            val value = renderAnnotationDefault(expression) ?: return null
            buildString {
                valueArgument.getArgumentName()?.asName?.render()?.let { name ->
                    append(name).append(" = ")
                }
                if (valueArgument.isSpread) append('*')
                append(value)
            }
        }
        return buildString {
            append('@')
            entry.useSiteTarget?.text?.removeSuffix(":")?.let { target ->
                append(target).append(':')
            }
            append(type)
            if (entry.valueArgumentList != null) append('(').append(arguments.joinToString(", ")).append(')')
        }
    }

    private fun renderTrustedSyntacticAnnotationType(
        entry: KtAnnotationEntry,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): String? {
        val userType = entry.typeReference?.typeElement as? KtUserType ?: return null
        if (userType.qualifier != null || userType.typeArguments.isNotEmpty()) return null
        val name = userType.referenceExpression?.getReferencedName() ?: return null
        KOTLIN_DEFAULT_ANNOTATION_TYPES[name]?.let { return it }
        val localAnnotations = (entry.containingKtFile.script?.declarations ?: entry.containingKtFile.declarations)
            .asSequence()
            .filterIsInstance<KtClass>()
            .filter(KtClass::isAnnotation)
            .filter { declaration -> declaration.name == name }
            .toList()
        if (localAnnotations.size == 1) return userType.referenceExpression?.text
        return renderSharedAnnotationType(entry, availableDeclarations)
    }

    private fun renderSharedAnnotationType(
        entry: KtAnnotationEntry,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): String? {
        val userType = entry.typeReference?.typeElement as? KtUserType ?: return null
        if (userType.qualifier != null || userType.typeArguments.isNotEmpty()) return null
        val name = userType.referenceExpression?.getReferencedName() ?: return null
        val annotationClasses = availableDeclarations[name].orEmpty().asSequence()
            .filter { declaration -> declaration.kind == EternalScriptDeclarationKind.CLASSIFIER }
            .flatMap { declaration ->
                declaration.mappings.asSequence().mapNotNull { mapping ->
                    mapping.sourcePointer.element as? KtClass
                }
            }
            .filter(KtClass::isAnnotation)
            .distinctBy { declaration ->
                declaration.containingKtFile.virtualFile?.url to declaration.textOffset
            }
            .toList()
        if (annotationClasses.size != 1) return null
        return userType.referenceExpression?.text
    }

    private fun canUseSemanticAnalysis(file: org.jetbrains.kotlin.psi.KtFile): Boolean =
        file.script == null || Idea262Facade.scriptDependenciesReady(file)

    private fun renderSelfContainedType(reference: KtTypeReference): String? {
        val hasStarImports = reference.containingKtFile.importDirectives.any { directive ->
            directive.importPath?.isAllUnder == true
        }
        val importedTypes = reference.containingKtFile.importDirectives.mapNotNull { directive ->
            val importPath = directive.importPath ?: return@mapNotNull null
            if (importPath.isAllUnder) return@mapNotNull null
            val target = importPath.fqName.render()
            val introducedName = importPath.alias?.asString() ?: importPath.fqName.shortName().asString()
            introducedName to target
        }.toMap()
        val shadowed = generateSequence(reference.parent) { element -> element.parent }
            .filterIsInstance<KtTypeParameterListOwner>()
            .flatMap { owner -> owner.typeParameters.asSequence() }
            .mapNotNull { parameter -> parameter.name }
            .toSet()
        val file = reference.containingKtFile
        val localClassifiers = (file.script?.declarations ?: file.declarations).asSequence()
            .filterIsInstance<KtNamedDeclaration>()
            .filter { declaration -> declaration is KtClassOrObject || declaration is KtTypeAlias }
            .mapNotNull { declaration -> declaration.name }
            .toSet()
        val replacements = mutableListOf<Pair<TextRange, String>>()
        reference.collectDescendantsOfType<KtUserType>().forEach { userType ->
            if (userType.qualifier != null) return@forEach
            val expression = userType.referenceExpression ?: return@forEach
            val name = expression.getReferencedName()
            if (name in shadowed || name in localClassifiers) return@forEach
            val target = importedTypes[name]
                ?: KOTLIN_DEFAULT_TYPES[name]?.takeUnless { hasStarImports }
                ?: return null
            replacements += expression.textRange to target
        }
        val startOffset = reference.textRange.startOffset
        val rendered = StringBuilder(reference.text)
        replacements.sortedByDescending { (range, _) -> range.startOffset }.forEach { (range, target) ->
            rendered.replace(range.startOffset - startOffset, range.endOffset - startOffset, target)
        }
        return rendered.toString()
    }

    private fun renderWithQualifiedTypes(element: KtElement): String? {
        val references = element.collectDescendantsOfType<KtTypeReference>()
        if (references.isEmpty()) return element.text
        val startOffset = element.textRange.startOffset
        val rendered = StringBuilder(element.text)
        references.sortedByDescending { reference -> reference.textRange.startOffset }.forEach { reference ->
            ProgressManager.checkCanceled()
            val replacement = renderType(reference) ?: return null
            val start = reference.textRange.startOffset - startOffset
            val end = reference.textRange.endOffset - startOffset
            rendered.replace(start, end, replacement)
        }
        return rendered.toString()
    }

    private fun inferType(
        property: KtProperty,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): String? {
        val value = property.initializer ?: return null
        return inferExpressionType(value, availableDeclarations)
    }

    /**
     * Handles only PSI shapes whose Kotlin type is unambiguous. Keeping this conservative avoids
     * entering a K2 analysis session for the most common literal declarations while preserving K2
     * as the source of truth for every non-trivial expression.
     */
    private fun cheapExpressionType(expression: KtExpression?): String? = when (expression) {
        is KtStringTemplateExpression -> "kotlin.String"
        is KtConstantExpression -> constantType(expression.text)
        else -> null
    }

    private fun constantType(text: String): String? {
        val value = text.replace("_", "")
        return when {
            value == "true" || value == "false" -> "kotlin.Boolean"
            value.startsWith('\'') && value.endsWith('\'') -> "kotlin.Char"
            value.endsWith('l', ignoreCase = true) && value.dropLast(1).toLongOrNull() != null -> "kotlin.Long"
            value.endsWith('f', ignoreCase = true) && value.dropLast(1).toFloatOrNull() != null -> "kotlin.Float"
            ('.' in value || 'e' in value.lowercase()) && value.toDoubleOrNull() != null -> "kotlin.Double"
            value.toIntOrNull() != null -> "kotlin.Int"
            else -> null
        }
    }

    private fun inferExpressionType(
        expression: KtExpression,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): String? {
        cheapExpressionType(expression)?.let { return it }
        return when (expression) {
            is KtPrefixExpression -> constantType(expression.text)
            is KtParenthesizedExpression -> expression.expression?.let { nested ->
                inferExpressionType(nested, availableDeclarations)
            }
            is KtNameReferenceExpression -> availableDeclarations[expression.getReferencedName()].orEmpty()
                .mapNotNull { declaration -> declaration.exposedType }
                .distinct()
                .singleOrNull()
                ?.let(::normalizeBuiltinType)
            is KtCallExpression -> inferCallType(expression, null, availableDeclarations)
            is KtDotQualifiedExpression -> {
                val call = expression.selectorExpression as? KtCallExpression ?: return null
                inferCallType(call, expression.receiverExpression, availableDeclarations)
            }
            else -> null
        }
    }

    private fun inferCallType(
        call: KtCallExpression,
        receiverExpression: KtExpression?,
        availableDeclarations: Map<String, List<EternalScriptRenderedDeclaration>>
    ): String? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val arguments = call.valueArguments.map { argument -> argument.getArgumentExpression() ?: return null }
        val argumentTypes = arguments.map { argument -> inferExpressionType(argument, availableDeclarations) }
        val receiverType = receiverExpression?.let { receiver ->
            inferExpressionType(receiver, availableDeclarations)
        }
        val candidates = availableDeclarations[name].orEmpty().filter { declaration ->
            when (declaration.kind) {
                EternalScriptDeclarationKind.CLASSIFIER -> receiverExpression == null
                EternalScriptDeclarationKind.FUNCTION -> {
                    declaration.parameterTypes.size == arguments.size &&
                        receiverMatches(declaration.receiverType, receiverType) &&
                        declaration.parameterTypes.zip(argumentTypes).all { (parameter, argument) ->
                            argument == null || sameType(parameter, argument)
                        }
                }
                EternalScriptDeclarationKind.PROPERTY -> false
            }
        }
        return candidates.mapNotNull { declaration -> declaration.exposedType }.distinct().singleOrNull()
            ?.let(::normalizeBuiltinType)
    }

    private fun receiverMatches(expected: String?, actual: String?): Boolean = when {
        expected == null -> actual == null
        actual == null -> false
        else -> sameType(expected, actual)
    }

    private fun sameType(expected: String, actual: String): Boolean {
        val expectedType = normalizeBuiltinType(expected).filterNot(Char::isWhitespace)
        val actualType = normalizeBuiltinType(actual).filterNot(Char::isWhitespace)
        return expectedType == actualType ||
            (expectedType.endsWith('?') && expectedType.removeSuffix("?") == actualType)
    }

    private fun normalizeBuiltinType(type: String): String {
        val suffix = if (type.endsWith('?')) "?" else ""
        val base = type.removeSuffix("?")
        val normalized = when (base) {
            "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long", "Short", "String", "Unit" ->
                "kotlin.$base"
            else -> base
        }
        return normalized + suffix
    }

    private fun isShared(declaration: KtDeclaration): Boolean = !declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)

    private const val CANCELLATION_CHECK_MASK: Int = 0x3f

    private val VISIBILITY_MODIFIERS = arrayOf(
        KtTokens.PUBLIC_KEYWORD,
        KtTokens.PROTECTED_KEYWORD,
        KtTokens.INTERNAL_KEYWORD,
        KtTokens.PRIVATE_KEYWORD
    )

    private val KOTLIN_DEFAULT_TYPES: Map<String, String> = mapOf(
        "Any" to "kotlin.Any",
        "Array" to "kotlin.Array",
        "Boolean" to "kotlin.Boolean",
        "Byte" to "kotlin.Byte",
        "Char" to "kotlin.Char",
        "CharSequence" to "kotlin.CharSequence",
        "Comparable" to "kotlin.Comparable",
        "Double" to "kotlin.Double",
        "Enum" to "kotlin.Enum",
        "Float" to "kotlin.Float",
        "Int" to "kotlin.Int",
        "Long" to "kotlin.Long",
        "Nothing" to "kotlin.Nothing",
        "Number" to "kotlin.Number",
        "Short" to "kotlin.Short",
        "String" to "kotlin.String",
        "Throwable" to "kotlin.Throwable",
        "Unit" to "kotlin.Unit",
        "Deprecated" to "kotlin.Deprecated",
        "OptIn" to "kotlin.OptIn",
        "PublishedApi" to "kotlin.PublishedApi",
        "RequiresOptIn" to "kotlin.RequiresOptIn",
        "Suppress" to "kotlin.Suppress",
        "UnsafeVariance" to "kotlin.UnsafeVariance",
        "JvmInline" to "kotlin.jvm.JvmInline",
        "JvmName" to "kotlin.jvm.JvmName",
        "JvmField" to "kotlin.jvm.JvmField",
        "JvmOverloads" to "kotlin.jvm.JvmOverloads",
        "JvmStatic" to "kotlin.jvm.JvmStatic",
        "MustBeDocumented" to "kotlin.annotation.MustBeDocumented",
        "Repeatable" to "kotlin.annotation.Repeatable",
        "Retention" to "kotlin.annotation.Retention",
        "Target" to "kotlin.annotation.Target",
        "Collection" to "kotlin.collections.Collection",
        "Iterable" to "kotlin.collections.Iterable",
        "Iterator" to "kotlin.collections.Iterator",
        "List" to "kotlin.collections.List",
        "ListIterator" to "kotlin.collections.ListIterator",
        "Map" to "kotlin.collections.Map",
        "MutableCollection" to "kotlin.collections.MutableCollection",
        "MutableIterable" to "kotlin.collections.MutableIterable",
        "MutableIterator" to "kotlin.collections.MutableIterator",
        "MutableList" to "kotlin.collections.MutableList",
        "MutableListIterator" to "kotlin.collections.MutableListIterator",
        "MutableMap" to "kotlin.collections.MutableMap",
        "MutableSet" to "kotlin.collections.MutableSet",
        "Set" to "kotlin.collections.Set"
    )

    private val KOTLIN_DEFAULT_ANNOTATION_TYPES: Map<String, String> = KOTLIN_DEFAULT_TYPES.filterKeys { name ->
        name in setOf(
            "Deprecated",
            "JvmField",
            "JvmInline",
            "JvmName",
            "JvmOverloads",
            "JvmStatic",
            "MustBeDocumented",
            "OptIn",
            "PublishedApi",
            "Repeatable",
            "RequiresOptIn",
            "Retention",
            "Suppress",
            "Target",
            "UnsafeVariance"
        )
    }

}
