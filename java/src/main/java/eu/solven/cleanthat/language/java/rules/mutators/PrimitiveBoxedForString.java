package eu.solven.cleanthat.language.java.rules.mutators;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

import cormoran.pepper.logging.PepperLogHelper;
import eu.solven.cleanthat.language.java.rules.AJavaParserRule;
import eu.solven.cleanthat.language.java.rules.meta.IClassTransformer;
import eu.solven.cleanthat.language.java.rules.meta.IRuleExternalUrls;

/**
 * Clean the way of converting primitives into {@link String}.
 *
 * @author Benoit Lacelle
 */
// https://jsparrow.github.io/rules/primitive-boxed-for-string.html
// https://rules.sonarsource.com/java/RSPEC-1158
public class PrimitiveBoxedForString extends AJavaParserRule implements IClassTransformer, IRuleExternalUrls {

	private static final Logger LOGGER = LoggerFactory.getLogger(PrimitiveBoxedForString.class);

	@Override
	public String minimalJavaVersion() {
		return "1.1";
	}

	@Override
	public String jsparrowUrl() {
		return "https://jsparrow.github.io/rules/reorder-modifiers.html";
	}

	@Override
	public String pmdUrl() {
		return "https://pmd.github.io/latest/pmd_rules_java_performance.html#unnecessarywrapperobjectcreation";
	}

	@Override
	public Optional<String> getPmdId() {
		// This matches multiple CleanThat rules
		return Optional.of("UnnecessaryWrapperObjectCreation");
	}

	@Override
	protected boolean processNotRecursively(Node node) {
		AtomicBoolean transformed = new AtomicBoolean();

		LOGGER.debug("{}", PepperLogHelper.getObjectAndClass(node));
		onMethodName(node, "toString", (methodNode, scope, type) -> {
			if (process(methodNode, scope, type)) {
				transformed.set(true);
			}
		});

		return transformed.get();
	}

	@SuppressWarnings({ "PMD.AvoidDeeplyNestedIfStmts", "PMD.CognitiveComplexity" })
	private boolean process(Node node, Expression scope, ResolvedType type) {
		if (!type.isReferenceType()) {
			return false;
		}
		LOGGER.debug("{} is referenceType", type);
		String primitiveQualifiedName = type.asReferenceType().getQualifiedName();
		if (Boolean.class.getName().equals(primitiveQualifiedName)
				|| Byte.class.getName().equals(primitiveQualifiedName)
				|| Short.class.getName().equals(primitiveQualifiedName)
				|| Integer.class.getName().equals(primitiveQualifiedName)
				|| Long.class.getName().equals(primitiveQualifiedName)
				|| Float.class.getName().equals(primitiveQualifiedName)
				|| Double.class.getName().equals(primitiveQualifiedName)) {
			LOGGER.debug("{} is AutoBoxed", type);
			if (scope instanceof ObjectCreationExpr) {
				// new Boolean(b).toString()
				ObjectCreationExpr creation = (ObjectCreationExpr) scope;
				NodeList<Expression> inputs = creation.getArguments();
				MethodCallExpr replacement =
						new MethodCallExpr(new NameExpr(creation.getType().getName()), "toString", inputs);
				LOGGER.info("Turning {} into {}", node, replacement);
				return node.replace(replacement);
			} else if (scope instanceof MethodCallExpr) {
				// Boolean.valueOf(b).toString()
				MethodCallExpr call = (MethodCallExpr) scope;

				if (!"valueOf".equals(call.getNameAsString()) || call.getScope().isEmpty()) {
					return false;
				}

				Expression calledScope = call.getScope().get();
				Optional<ResolvedType> calledType = optResolvedType(calledScope);

				if (calledType.isEmpty() || !calledType.get().isReferenceType()) {
					return false;
				}

				ResolvedReferenceType referenceType = calledType.get().asReferenceType();

				if (referenceType.hasName() && primitiveQualifiedName.equals(referenceType.getQualifiedName())) {
					MethodCallExpr replacement = new MethodCallExpr(calledScope, "toString", call.getArguments());

					return node.replace(replacement);
				}
			}
		}

		return false;
	}
}
