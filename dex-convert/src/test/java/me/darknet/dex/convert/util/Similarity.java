package me.darknet.dex.convert.util;

import org.apache.commons.text.similarity.LevenshteinDistance;
import software.coley.sourcesolver.Parser;
import software.coley.sourcesolver.model.ArrayAccessExpressionModel;
import software.coley.sourcesolver.model.ArrayDeclarationExpressionModel;
import software.coley.sourcesolver.model.AssignmentExpressionModel;
import software.coley.sourcesolver.model.BinaryExpressionModel;
import software.coley.sourcesolver.model.BlockStatementModel;
import software.coley.sourcesolver.model.CaseModel;
import software.coley.sourcesolver.model.CastExpressionModel;
import software.coley.sourcesolver.model.CatchModel;
import software.coley.sourcesolver.model.ClassModel;
import software.coley.sourcesolver.model.CompilationUnitModel;
import software.coley.sourcesolver.model.ConditionalExpressionModel;
import software.coley.sourcesolver.model.ConstCaseLabelModel;
import software.coley.sourcesolver.model.DoWhileLoopStatementModel;
import software.coley.sourcesolver.model.EnhancedForLoopStatementModel;
import software.coley.sourcesolver.model.ExpressionStatementModel;
import software.coley.sourcesolver.model.ForLoopStatementModel;
import software.coley.sourcesolver.model.IfStatementModel;
import software.coley.sourcesolver.model.ImplementsModel;
import software.coley.sourcesolver.model.ImportModel;
import software.coley.sourcesolver.model.InstanceofExpressionModel;
import software.coley.sourcesolver.model.LabeledStatementModel;
import software.coley.sourcesolver.model.LiteralExpressionModel;
import software.coley.sourcesolver.model.MemberSelectExpressionModel;
import software.coley.sourcesolver.model.MethodBodyModel;
import software.coley.sourcesolver.model.MethodInvocationExpressionModel;
import software.coley.sourcesolver.model.MethodModel;
import software.coley.sourcesolver.model.MethodReferenceExpressionModel;
import software.coley.sourcesolver.model.Model;
import software.coley.sourcesolver.model.ModifiersModel;
import software.coley.sourcesolver.model.NameExpressionModel;
import software.coley.sourcesolver.model.NewClassExpressionModel;
import software.coley.sourcesolver.model.PackageModel;
import software.coley.sourcesolver.model.ParenthesizedExpressionModel;
import software.coley.sourcesolver.model.ReturnStatementModel;
import software.coley.sourcesolver.model.SwitchExpressionModel;
import software.coley.sourcesolver.model.SwitchStatementModel;
import software.coley.sourcesolver.model.SynchronizedStatementModel;
import software.coley.sourcesolver.model.ThrowStatementModel;
import software.coley.sourcesolver.model.TryStatementModel;
import software.coley.sourcesolver.model.TypeModel;
import software.coley.sourcesolver.model.TypeParameterModel;
import software.coley.sourcesolver.model.UnaryExpressionModel;
import software.coley.sourcesolver.model.UnknownExpressionModel;
import software.coley.sourcesolver.model.VariableModel;
import software.coley.sourcesolver.model.WhileLoopStatementModel;
import software.coley.sourcesolver.model.YieldStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Crude AST similarity metric using javac tree traversal.
 * <p>
 * This is by no means perfect, a lot of the weights are just arbitrarily picked based on a few real-world samples.
 * We just want a rough estimate of similarity that can catch major structural differences and give us some confidence
 * that the decompiled code is at least somewhat close to the original version, without being too strict and penalizing
 * minor differences that don't affect the overall structure or behavior of the code.
 */
public class Similarity {
	private static final Parser SHARED_PARSER = new Parser();

	private static final double SEVERE_SIZE_MISMATCH = 0.40;
	private static final double MILD_SIZE_MISMATCH = 0.75;

	/**
	 * Computes a similarity score between two Java source code snippets by parsing them into ASTs and comparing their structure and content.
	 *
	 * @param src1
	 * 		First Java source code snippet.
	 * @param src2
	 * 		Second Java source code snippet.
	 *
	 * @return Similarity score between 0 and 1, where 1 means identical and 0 means completely different.
	 */
	public static double similarity(String src1, String src2) {
		CompilationUnitModel unit1, unit2;
		synchronized (SHARED_PARSER) {
			// SHARED_PARSER is not thread-safe, so we synchronize on it to prevent concurrent parsing.
			// This allows us to reuse the same parser instance across multiple similarity computations
			// without having to create a new one each time, which can be expensive.
			unit1 = SHARED_PARSER.parse(src1);
			unit2 = SHARED_PARSER.parse(src2);
		}
		Context ctx = new Context();
		double similarity = visit(unit1, unit2, ctx);
//		System.out.println("Similarity: " + similarity);
//		System.out.println("Total nodes compared: " + ctx.totalNodes);
//		System.out.println("Matching nodes: " + ctx.matchingNodes);
//		System.out.println("Mismatches:");
//		ctx.mismatches.forEach(m -> System.out.println("  - " + m));
		return similarity;
	}

	/**
	 * Visits two models in parallel and computes a similarity score between 0 and 1.
	 *
	 * @param a
	 * 		First model.
	 * @param b
	 * 		Second model.
	 * @param ctx
	 * 		Context for logging mismatches and tracking stats.
	 *
	 * @return Similarity score between 0 and 1, where 1 means identical and 0 means completely different.
	 */
	private static double visit(Model a, Model b, Context ctx) {
		Class<? extends Model> typeA = a.getClass();
		Class<? extends Model> typeB = b.getClass();

		if (typeA != typeB) {
			ctx.logMismatch("Different types: %s vs %s", typeA.getSimpleName(), typeB.getSimpleName());
			return levenshteinSimilarity(a, b);
		}

		double content = compareContent(a, b, ctx);

		ctx.totalNodes++;
		if (content > 0.95)
			ctx.matchingNodes++;
		else if (content < 0.5)
			ctx.logMismatch("Low similarity for %s: %.2f", typeA.getSimpleName(), content);

		return Math.clamp(content, 0, 1);
	}

	/**
	 * Compares the content of two models of the same type and computes a similarity score between 0 and 1.
	 *
	 * @param a
	 * 		First model.
	 * @param b
	 * 		Second model.
	 * @param ctx
	 * 		Context for logging mismatches and tracking stats.
	 *
	 * @return Similarity score between 0 and 1, where 1 means identical and 0 means completely different.
	 */
	private static double compareContent(Model a, Model b, Context ctx) {
		return switch (a) {
			case ArrayAccessExpressionModel arrayAccess -> {
				ArrayAccessExpressionModel o = (ArrayAccessExpressionModel) b;
				double array = visit(arrayAccess.getExpression(), o.getExpression(), ctx);
				double index = visit(arrayAccess.getIndex(), o.getIndex(), ctx);
				yield (array + index) / 2.0;
			}
			case ArrayDeclarationExpressionModel arrayDec -> {
				ArrayDeclarationExpressionModel o = (ArrayDeclarationExpressionModel) b;
				double type = levenshteinSimilarity(arrayDec.getType(), o.getType());
				double dims = arrayDec.getDimensions() == o.getDimensions() ? 1.0 : 0.0;
				yield (type * 0.7 + dims * 0.3);
			}
			case AssignmentExpressionModel assign -> {
				AssignmentExpressionModel o = (AssignmentExpressionModel) b;
				double left = visit(assign.getExpression(), o.getExpression(), ctx);
				double right = visit(assign.getVariable(), o.getVariable(), ctx);
				double op = assign.getOperator() == o.getOperator() ? 1.0 : 0.0;
				yield (left * 0.4 + right * 0.4 + op * 0.2);
			}
			case BinaryExpressionModel binary -> {
				BinaryExpressionModel o = (BinaryExpressionModel) b;
				double left = visit(binary.getLeft(), o.getLeft(), ctx);
				double right = visit(binary.getRight(), o.getRight(), ctx);
				double op = binary.getOperator() == o.getOperator() ? 1.0 : 0.0;
				yield (left * 0.4 + right * 0.4 + op * 0.2);
			}
			case BlockStatementModel block -> {
				BlockStatementModel o = (BlockStatementModel) b;
				yield compareOrderedChildren(block.getStatements(), o.getStatements(), ctx);
			}
			case CaseModel cas -> {
				CaseModel o = (CaseModel) b;
				double body = cas.getBody() == null || o.getBody() == null ? 0 : visit(cas.getBody(), o.getBody(), ctx);
				double labels = compareOrderedChildren(cas.getLabels(), o.getLabels(), ctx);
				double exprs = compareOrderedChildren(cas.getExpressions(), o.getExpressions(), ctx);
				double statments = compareOrderedChildren(cas.getStatements(), o.getStatements(), ctx);
				yield (body * 0.4 + labels * 0.2 + exprs * 0.2 + statments * 0.2);
			}
			case CastExpressionModel cast -> {
				CastExpressionModel o = (CastExpressionModel) b;
				double type = levenshteinSimilarity(cast.getType(), o.getType());
				double expr = visit(cast.getExpression(), o.getExpression(), ctx);
				yield (type * 0.5 + expr * 0.5);
			}
			case CatchModel catchModel -> {
				CatchModel o = (CatchModel) b;
				double param = visit(catchModel.getParameter(), o.getParameter(), ctx);
				double body = visit(catchModel.getBlock(), o.getBlock(), ctx);
				yield (param * 0.4 + body * 0.6);
			}
			case ClassModel c -> {
				ClassModel o = (ClassModel) b;
				double name = relaxedNameMatch(c.getName(), o.getName());
				double mods = modifiersMatch(c.getModifiers().getModifiers(), o.getModifiers().getModifiers());
				double implement = visit(c.getImplements(), o.getImplements(), ctx);
				double extend = visit(c.getExtends(), o.getExtends(), ctx);
				double fields = compareBagChildren(c.getFields(), o.getFields(), ctx);
				double methods = compareBagChildren(c.getMethods(), o.getMethods(), ctx);
				yield (name * 0.2 + mods * 0.1 + implement * 0.1 + extend * 0.1 + fields * 0.25 + methods * 0.25);
			}
			case CompilationUnitModel unit -> {
				CompilationUnitModel o = (CompilationUnitModel) b;
				double pkg = Objects.equals(unit.getPackage(), o.getPackage()) ? 1.0 : 0.0;
				double imports = compareBagChildren(unit.getImports(), o.getImports(), ctx);
				double classes = compareBagChildren(unit.getDeclaredClasses(), o.getDeclaredClasses(), ctx);
				yield (pkg * 0.2 + imports * 0.3 + classes * 0.5);
			}
			case ConditionalExpressionModel cond -> {
				ConditionalExpressionModel o = (ConditionalExpressionModel) b;
				double condExpr = visit(cond.getCondition(), o.getCondition(), ctx);
				double trueExpr = visit(cond.getTrueCase(), o.getTrueCase(), ctx);
				double falseExpr = visit(cond.getFalseCase(), o.getFalseCase(), ctx);
				yield (condExpr * 0.4 + trueExpr * 0.3 + falseExpr * 0.3);
			}
			case ConstCaseLabelModel constLabel -> {
				ConstCaseLabelModel o = (ConstCaseLabelModel) b;
				yield visit(constLabel.getConstExpr(), o.getConstExpr(), ctx);
			}
			case DoWhileLoopStatementModel doWhile -> {
				DoWhileLoopStatementModel o = (DoWhileLoopStatementModel) b;
				double body = visit(doWhile.getStatement(), o.getStatement(), ctx);
				double cond = visit(doWhile.getCondition(), o.getCondition(), ctx);
				yield (body * 0.6 + cond * 0.4);
			}
			case EnhancedForLoopStatementModel forEach -> {
				EnhancedForLoopStatementModel o = (EnhancedForLoopStatementModel) b;
				double var = visit(forEach.getVariable(), o.getVariable(), ctx);
				double iterable = visit(forEach.getExpression(), o.getExpression(), ctx);
				double body = visit(forEach.getStatement(), o.getStatement(), ctx);
				yield (var * 0.3 + iterable * 0.3 + body * 0.4);
			}
			case ExpressionStatementModel exprStmt -> {
				ExpressionStatementModel o = (ExpressionStatementModel) b;
				yield visit(exprStmt.getExpression(), o.getExpression(), ctx);
			}
			case ForLoopStatementModel forLoop -> {
				ForLoopStatementModel o = (ForLoopStatementModel) b;
				double init = compareOrderedChildren(forLoop.getInitializerStatements(), o.getInitializerStatements(), ctx);
				double cond = visit(forLoop.getCondition(), o.getCondition(), ctx);
				double update = compareOrderedChildren(forLoop.getUpdateStatements(), o.getUpdateStatements(), ctx);
				double body = visit(forLoop.getStatement(), o.getStatement(), ctx);
				yield (init * 0.3 + cond * 0.2 + update * 0.2 + body * 0.3);
			}
			case IfStatementModel ifStmt -> {
				IfStatementModel o = (IfStatementModel) b;
				double cond = visit(ifStmt.getCondition(), o.getCondition(), ctx);
				double thenBranch = visit(ifStmt.getThenCaseStatement(), o.getThenCaseStatement(), ctx);
				double elseBranch = ifStmt.getElseCaseStatement() == null || o.getElseCaseStatement() == null ? 0 : visit(ifStmt.getElseCaseStatement(), o.getElseCaseStatement(), ctx);
				yield (cond * 0.4 + thenBranch * 0.4 + elseBranch * 0.2);
			}
			case ImplementsModel impl -> {
				ImplementsModel o = (ImplementsModel) b;
				yield compareOrderedChildren(impl.getImplementedClassNames(), o.getImplementedClassNames(), ctx);
			}
			case ImportModel imp -> {
				ImportModel o = (ImportModel) b;
				double name = relaxedNameMatch(imp.getName(), o.getName());
				double staticFlag = imp.isStatic() == o.isStatic() ? 1.0 : 0.0;
				yield (name * 0.7 + staticFlag * 0.3);
			}
			case InstanceofExpressionModel inst -> {
				InstanceofExpressionModel o = (InstanceofExpressionModel) b;
				double expr = visit(inst.getExpression(), o.getExpression(), ctx);
				double type = levenshteinSimilarity(inst.getType(), o.getType());
				yield (expr * 0.5 + type * 0.5);
			}
			case LabeledStatementModel label -> {
				LabeledStatementModel o = (LabeledStatementModel) b;
				double labelName = label.getLabelName() == null || o.getLabelName() == null ? 0 : relaxedNameMatch(label.getLabelName(), o.getLabelName());
				double stmt = visit(label.getStatement(), o.getStatement(), ctx);
				yield (labelName * 0.3 + stmt * 0.7);
			}
			case LiteralExpressionModel lit -> {
				LiteralExpressionModel o = (LiteralExpressionModel) b;
				yield Objects.equals(lit.getContent(), o.getContent()) ? 1.0 : 0.0;
			}
			case MemberSelectExpressionModel select -> {
				MemberSelectExpressionModel o = (MemberSelectExpressionModel) b;
				double expr = visit(select.getContext(), o.getContext(), ctx);
				double member = relaxedNameMatch(select.getName(), o.getName());
				yield (expr * 0.5 + member * 0.5);
			}
			case MethodBodyModel body -> {
				MethodBodyModel o = (MethodBodyModel) b;
				yield compareOrderedChildren(body.getStatements(), o.getStatements(), ctx);
			}
			case MethodInvocationExpressionModel invoke -> {
				MethodInvocationExpressionModel o = (MethodInvocationExpressionModel) b;
				double target = visit(invoke.getMethodSelect(), o.getMethodSelect(), ctx);
				double receiver = invoke.getReceiver() == null || o.getReceiver() == null ? 0 : visit(invoke.getReceiver(), o.getReceiver(), ctx);
				double args = compareOrderedChildren(invoke.getArguments(), o.getArguments(), ctx);
				yield (target * 0.4 + receiver * 0.3 + args * 0.3);
			}
			case MethodModel method -> {
				MethodModel o = (MethodModel) b;
				double name = relaxedNameMatch(method.getName(), o.getName());
				double desc = descriptorMatch(method, o);
				double mods = modifiersMatch(method.getModifiers().getModifiers(), o.getModifiers().getModifiers());
				double code = method.getMethodBody() == null || o.getMethodBody() == null ? 0 : visit(method.getMethodBody(), o.getMethodBody(), ctx);
				yield (name * 0.2 + desc * 0.1 + mods * 0.1 + code * 0.6);
			}
			case MethodReferenceExpressionModel ref -> {
				MethodReferenceExpressionModel o = (MethodReferenceExpressionModel) b;
				double target = visit(ref.getNameModel(), o.getNameModel(), ctx);
				double receiver = visit(ref.getQualifier(), o.getQualifier(), ctx);
				yield (target * 0.6 + receiver * 0.4);
			}
			case ModifiersModel modifiers -> {
				ModifiersModel o = (ModifiersModel) b;
				yield modifiersMatch(modifiers.getModifiers(), o.getModifiers());
			}
			case NameExpressionModel name -> {
				NameExpressionModel o = (NameExpressionModel) b;
				yield relaxedNameMatch(name.getName(), o.getName());
			}
			case NewClassExpressionModel newClass -> {
				NewClassExpressionModel o = (NewClassExpressionModel) b;
				double type = levenshteinSimilarity(newClass.getIdentifier(), o.getIdentifier());
				double args = compareOrderedChildren(newClass.getArguments(), o.getArguments(), ctx);
				double enclosing = newClass.getEnclosingExpression() == null || o.getEnclosingExpression() == null ? 0 : visit(newClass.getEnclosingExpression(), o.getEnclosingExpression(), ctx);
				double body = newClass.getBody() == null || o.getBody() == null ? 0 : visit(newClass.getBody(), o.getBody(), ctx);
				yield (type * 0.4 + args * 0.3 + enclosing * 0.2 + body * 0.1);
			}
			case PackageModel pack -> {
				PackageModel o = (PackageModel) b;
				yield Objects.equals(pack.getName(), o.getName()) ? 1.0 : 0.0;
			}
			case ParenthesizedExpressionModel paren -> {
				ParenthesizedExpressionModel o = (ParenthesizedExpressionModel) b;
				yield visit(paren.getExpression(), o.getExpression(), ctx);
			}
			case ReturnStatementModel ret -> {
				ReturnStatementModel o = (ReturnStatementModel) b;
				if (ret.getExpression() == null && o.getExpression() == null) yield 1.0;
				if (ret.getExpression() == null || o.getExpression() == null) yield 0.0;
				yield visit(ret.getExpression(), o.getExpression(), ctx);
			}
			case SwitchExpressionModel switchExpr -> {
				SwitchExpressionModel o = (SwitchExpressionModel) b;
				double selector = visit(switchExpr.getExpression(), o.getExpression(), ctx);
				double cases = compareOrderedChildren(switchExpr.getCases(), o.getCases(), ctx);
				yield (selector * 0.4 + cases * 0.6);
			}
			case SwitchStatementModel switchStmt -> {
				SwitchStatementModel o = (SwitchStatementModel) b;
				double selector = visit(switchStmt.getExpression(), o.getExpression(), ctx);
				double cases = compareOrderedChildren(switchStmt.getCases(), o.getCases(), ctx);
				yield (selector * 0.4 + cases * 0.6);
			}
			case SynchronizedStatementModel sync -> {
				SynchronizedStatementModel o = (SynchronizedStatementModel) b;
				double lock = visit(sync.getExpression(), o.getExpression(), ctx);
				double body = visit(sync.getBlock(), o.getBlock(), ctx);
				yield (lock * 0.4 + body * 0.6);
			}
			case ThrowStatementModel throwStmt -> {
				ThrowStatementModel o = (ThrowStatementModel) b;
				yield visit(throwStmt.getExpression(), o.getExpression(), ctx);
			}
			case TryStatementModel tryStmt -> {
				TryStatementModel o = (TryStatementModel) b;
				double tryBlock = visit(tryStmt.getBlock(), o.getBlock(), ctx);
				double catches = compareOrderedChildren(tryStmt.getCatches(), o.getCatches(), ctx);
				double finallyBlock = tryStmt.getFinallyBlock() == null || o.getFinallyBlock() == null ? 0 : visit(tryStmt.getFinallyBlock(), o.getFinallyBlock(), ctx);
				yield (tryBlock * 0.5 + catches * 0.3 + finallyBlock * 0.2);
			}
			case TypeModel type -> {
				TypeModel o = (TypeModel) b;
				yield levenshteinSimilarity(type, o);
			}
			case TypeParameterModel typeParam -> {
				TypeParameterModel o = (TypeParameterModel) b;
				double name = relaxedNameMatch(typeParam.getName(), o.getName());
				double bounds = compareOrderedChildren(typeParam.getBounds(), o.getBounds(), ctx);
				yield (name * 0.4 + bounds * 0.6);
			}
			case UnaryExpressionModel un -> {
				UnaryExpressionModel o = (UnaryExpressionModel) b;
				yield un.getOperator() == o.getOperator() ? 1.0 : 0.0;
			}
			case VariableModel v -> {
				VariableModel o = (VariableModel) b;
				double name = relaxedNameMatch(v.getName(), o.getName());
				double type = levenshteinSimilarity(v.getType(), o.getType());
				double mods = modifiersMatch(v.getModifiers().getModifiers(), o.getModifiers().getModifiers());
				yield (name * 0.3 + type * 0.4 + mods * 0.3);
			}
			case WhileLoopStatementModel whileLoop -> {
				WhileLoopStatementModel o = (WhileLoopStatementModel) b;
				double cond = visit(whileLoop.getCondition(), o.getCondition(), ctx);
				double body = visit(whileLoop.getStatement(), o.getStatement(), ctx);
				yield (cond * 0.4 + body * 0.6);
			}
			case YieldStatementModel yieldStmt -> {
				YieldStatementModel o = (YieldStatementModel) b;
				yield visit(yieldStmt.getExpression(), o.getExpression(), ctx);
			}
			case UnknownExpressionModel unknown -> {
				// This is a catch-all for junk output from the decompiler that doesn't fit into a proper AST node.
				// We can sometimes get partial text of this junk, so we can at least compare that and give a rough
				// similarity score, which is better than treating it as completely different.
				UnknownExpressionModel o = (UnknownExpressionModel) b;
				yield levenshteinSimilarity(unknown.getContent(), o.getContent());
			}
			default -> {
				System.err.println("Warning: No specific comparison logic for " + a.getClass().getSimpleName() + ", using default similarity");
				yield 1.0;
			}
		};
	}

	/**
	 * Compares two names with some relaxation to account for common decompiler-generated identifiers.
	 *
	 * @param a
	 * 		First name.
	 * @param b
	 * 		Second name.
	 *
	 * @return Similarity score between 0 and 1, where 1 means identical or both look like synthetic names, and 0 means different.
	 */
	private static double relaxedNameMatch(String a, String b) {
		if (a.equals(b))
			return 1.0;

		// Decompilers often use var1, this$0, $assertX --> we don't really care about the exact name in these cases
		if (looksSynthetic(a) && looksSynthetic(b))
			return 0.9;

		// Anything else, get fuzzy similarity.
		return levenshteinSimilarity(a, b);
	}

	private static boolean looksSynthetic(String name) {
		return name.startsWith("var") || name.contains("$") || name.contains(".");
	}

	private static double modifiersMatch(Set<String> a, Set<String> b) {
		return a.equals(b) ? 1.0 : 0.6;
	}

	private static double descriptorMatch(MethodModel a, MethodModel b) {
		return levenshteinSimilarity(a.getReturnType(), b.getReturnType()) *
				listSimilarity(a.getParameters(), b.getParameters(), Similarity::levenshteinSimilarity);
	}

	private static double levenshteinSimilarity(Model a, Model b) {
		// Assuming a/b are types, their toString() should give a reasonable representation (like "java.util.List<String>")
		String as = a.toString();
		String bs = b.toString();
		return levenshteinSimilarity(as, bs);
	}

	private static double levenshteinSimilarity(String as, String bs) {
		if (as.isEmpty() && bs.isEmpty())
			return 1.0;
		int distance = LevenshteinDistance.getDefaultInstance().apply(as, bs);
		int maxLen = Math.max(as.length(), bs.length());
		return (double) (maxLen - distance) / maxLen;
	}

	private static <T> double listSimilarity(
			List<T> listA,
			List<T> listB,
			BiFunction<? super T, ? super T, Double> elementSimilarity) {

		int sizeA = listA.size();
		int sizeB = listB.size();

		if (sizeA == 0 && sizeB == 0) {
			return 1.0;
		}
		if (sizeA == 0 || sizeB == 0) {
			return 0.0;
		}

		double sizePenalty = 1.0;
		if (sizeA != sizeB) {
			double ratio = (double) Math.min(sizeA, sizeB) / Math.max(sizeA, sizeB);
			sizePenalty = 0.65 + (0.35 * ratio);
		}

		// Fast path: same size --> direct ordered comparison (most common for params, args, etc.)
		if (sizeA == sizeB) {
			double sum = 0.0;
			for (int i = 0; i < sizeA; i++) {
				sum += elementSimilarity.apply(listA.get(i), listB.get(i));
			}
			return sizePenalty * (sum / sizeA);
		}

		// Slower path: different sizes --> greedy best matching (bag-of-children style)
		List<T> leftA = new ArrayList<>(listA);
		List<T> leftB = new ArrayList<>(listB);
		double totalScore = 0.0;
		int matchedCount = 0;

		while (!leftA.isEmpty() && !leftB.isEmpty()) {
			double bestSim = -1.0;
			int bestIdxA = -1;
			int bestIdxB = -1;

			for (int i = 0; i < leftA.size(); i++) {
				for (int j = 0; j < leftB.size(); j++) {
					double sim = elementSimilarity.apply(leftA.get(i), leftB.get(j));
					if (sim > bestSim) {
						bestSim = sim;
						bestIdxA = i;
						bestIdxB = j;
					}
				}
			}

			// Early stop if best match is too weak
			if (bestSim < 0.30) {
				break;
			}

			totalScore += bestSim;
			matchedCount++;

			leftA.remove(bestIdxA);
			leftB.remove(bestIdxB);
		}

		// Remaining unmatched items penalize the score
		int unmatched = (sizeA + sizeB) - 2 * matchedCount;
		double unmatchedPenalty = unmatched * 0.45;  // ← tune

		double matchedAvg = matchedCount > 0 ? totalScore / matchedCount : 0.0;
		double finalScore = (totalScore + unmatchedPenalty) / (sizeA + sizeB);

		return sizePenalty * Math.max(0.0, Math.min(1.0, finalScore));
	}

	private static double compareOrderedChildren(List<? extends Model> ca, List<? extends Model> cb, Context ctx) {
		int na = ca.size(), nb = cb.size();
		if (na == 0 && nb == 0) return 1.0;
		if (na == 0 || nb == 0) return 0.0;

		if (na != nb) {
			double ratio = (double) Math.min(na, nb) / Math.max(na, nb);
			return MILD_SIZE_MISMATCH + (SEVERE_SIZE_MISMATCH - MILD_SIZE_MISMATCH) * (1 - ratio);
		}

		double sum = 0;
		for (int i = 0; i < na; i++) {
			sum += visit(ca.get(i), cb.get(i), ctx);
		}
		return sum / na;
	}

	private static double compareBagChildren(List<? extends Model> ca, List<? extends Model> cb, Context ctx) {
		if (ca.isEmpty() && cb.isEmpty()) return 1.0;
		if (ca.isEmpty() || cb.isEmpty()) return 0.01;

		// Greedy best-match (for small lists this is fine; for large --> memoize or use better algo)
		List<Model> aLeft = new ArrayList<>(ca);
		List<Model> bLeft = new ArrayList<>(cb);
		double similaritySum = 0;
		int matched = 0;

		while (!aLeft.isEmpty() && !bLeft.isEmpty()) {
			double best = -1;
			int ai = -1, bi = -1;

			for (int i = 0; i < aLeft.size(); i++) {
				for (int j = 0; j < bLeft.size(); j++) {
					double s = visit(aLeft.get(i), bLeft.get(j), ctx);
					if (s > best) {
						best = s;
						ai = i;
						bi = j;
					}
				}
			}

			if (best < 0.35) break;  // too different - treat as unmatched

			similaritySum += best;
			matched++;
			aLeft.remove(ai);
			bLeft.remove(bi);
		}

		// Return average similarity of matched pairs, penalized by unmatched items
		int unmatched = (ca.size() + cb.size()) - (2 * matched);
		double unmatchedPenalty = unmatched * 0.75; // penalty closer to 0 is harsher, closer to 1 is more lenient
		double avgSimilarity = matched > 0 ? similaritySum / matched : 0.0;
		double finalScore = (similaritySum + unmatchedPenalty) / ((ca.size() + cb.size()) / 2.0);
		double result = Math.clamp(finalScore, 0.0, 1.0);
		return result;
	}

	private static class Context {
		private int totalNodes = 0;
		private int matchingNodes = 0;
		private final List<String> mismatches = new ArrayList<>();

		void logMismatch(String fmt, Object... args) {
			mismatches.add(String.format(fmt, args));
		}
	}
}
