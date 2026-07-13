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
				double body = optionalSimilarity(cas.getBody(), o.getBody(), ctx);
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
				double elseBranch = optionalSimilarity(ifStmt.getElseCaseStatement(), o.getElseCaseStatement(), ctx);
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
				double labelName = optionalStringSimilarity(label.getLabelName(), o.getLabelName());
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
				double receiver = optionalSimilarity(invoke.getReceiver(), o.getReceiver(), ctx);
				double args = compareOrderedChildren(invoke.getArguments(), o.getArguments(), ctx);
				yield (target * 0.4 + receiver * 0.3 + args * 0.3);
			}
			case MethodModel method -> {
				MethodModel o = (MethodModel) b;
				double name = relaxedNameMatch(method.getName(), o.getName());
				double desc = descriptorMatch(method, o);
				double mods = modifiersMatch(method.getModifiers().getModifiers(), o.getModifiers().getModifiers());
				double code = optionalSimilarity(method.getMethodBody(), o.getMethodBody(), ctx);
				double defaultValue = optionalSimilarity(method.getDefaultValue(), o.getDefaultValue(), ctx);
				yield (name * 0.2 + desc * 0.1 + mods * 0.1 + code * 0.5 + defaultValue * 0.1);
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
				double enclosing = optionalSimilarity(newClass.getEnclosingExpression(), o.getEnclosingExpression(), ctx);
				double body = optionalSimilarity(newClass.getBody(), o.getBody(), ctx);
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
				yield optionalSimilarity(ret.getExpression(), o.getExpression(), ctx);
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
				double finallyBlock = optionalSimilarity(tryStmt.getFinallyBlock(), o.getFinallyBlock(), ctx);
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
				double operand = visit(un.getExpression(), o.getExpression(), ctx);
				double operator = un.getOperator() == o.getOperator() ? 1.0 : 0.0;
				yield operator * 0.5 + operand * 0.5;
			}
			case VariableModel v -> {
				VariableModel o = (VariableModel) b;
				double name = relaxedNameMatch(v.getName(), o.getName());
				double type = levenshteinSimilarity(v.getType(), o.getType());
				double mods = modifiersMatch(v.getModifiers().getModifiers(), o.getModifiers().getModifiers());
				double value = optionalSimilarity(v.getValue(), o.getValue(), ctx);
				yield (name * 0.2 + type * 0.3 + mods * 0.2 + value * 0.3);
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
				ctx.logMismatch("Fallback comparison for model type %s", a.getClass().getSimpleName());
				yield levenshteinSimilarity(a.toString(), b.toString());
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
		return alignedScore(matchingScores(ca, cb), ca, cb, ctx);
	}

	private static double compareBagChildren(List<? extends Model> ca, List<? extends Model> cb, Context ctx) {
		if (ca.isEmpty() && cb.isEmpty()) return 1.0;
		if (ca.isEmpty() || cb.isEmpty()) return 0.0;
		double[][] scores = matchingScores(ca, cb);
		int n = Math.max(ca.size(), cb.size());
		double[] u = new double[n + 1], v = new double[n + 1];
		int[] p = new int[n + 1], way = new int[n + 1];
		for (int row = 1; row <= n; row++) {
			p[0] = row;
			int col0 = 0;
			double[] min = new double[n + 1];
			java.util.Arrays.fill(min, Double.POSITIVE_INFINITY);
			boolean[] used = new boolean[n + 1];
			do {
				used[col0] = true;
				int row0 = p[col0], col1 = 0;
				double delta = Double.POSITIVE_INFINITY;
				for (int col = 1; col <= n; col++) {
					if (used[col]) continue;
					double weight = row0 <= ca.size() && col <= cb.size() ? scores[row0 - 1][col - 1] : 0.0;
					double cur = 1.0 - weight - u[row0] - v[col];
					if (cur < min[col]) { min[col] = cur; way[col] = col0; }
					if (min[col] < delta) { delta = min[col]; col1 = col; }
				}
				for (int col = 0; col <= n; col++) {
					if (used[col]) { u[p[col]] += delta; v[col] -= delta; }
					else min[col] -= delta;
				}
				col0 = col1;
			} while (p[col0] != 0);
			do { int col1 = way[col0]; p[col0] = p[col1]; col0 = col1; } while (col0 != 0);
		}
		double total = 0.0;
		for (int col = 1; col <= cb.size(); col++) {
			int row = p[col];
			if (row >= 1 && row <= ca.size() && scores[row - 1][col - 1] > 0.0)
				total += visit(ca.get(row - 1), cb.get(col - 1), ctx);
		}
		return total / n;
	}

	private static double alignedScore(double[][] scores, List<? extends Model> left,
	                                  List<? extends Model> right, Context ctx) {
		int na = scores.length, nb = na == 0 ? 0 : scores[0].length;
		double[][] dp = new double[na + 1][nb + 1];
		for (int i = 1; i <= na; i++) for (int j = 1; j <= nb; j++)
			dp[i][j] = Math.max(Math.max(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1] + scores[i - 1][j - 1]);
		List<int[]> selected = new ArrayList<>();
		int i = na, j = nb;
		while (i > 0 && j > 0) {
			double match = dp[i - 1][j - 1] + scores[i - 1][j - 1];
			if (match >= dp[i - 1][j] && match >= dp[i][j - 1] && Math.abs(dp[i][j] - match) < 1e-12)
				selected.add(new int[]{--i, --j});
			else if (dp[i - 1][j] >= dp[i][j - 1]) i--;
			else j--;
		}
		double total = 0.0;
		for (int k = selected.size() - 1; k >= 0; k--) {
			int[] pair = selected.get(k);
			if (scores[pair[0]][pair[1]] > 0.0) total += visit(left.get(pair[0]), right.get(pair[1]), ctx);
		}
		return total / Math.max(na, nb);
	}

	private static double[][] matchingScores(List<? extends Model> left, List<? extends Model> right) {
		String[] leftKeys = left.stream().map(Similarity::matchingKey).toArray(String[]::new);
		String[] rightKeys = right.stream().map(Similarity::matchingKey).toArray(String[]::new);
		double[][] scores = new double[left.size()][right.size()];
		for (int i = 0; i < left.size(); i++) for (int j = 0; j < right.size(); j++)
			if (left.get(i).getClass() == right.get(j).getClass()) scores[i][j] = matchingHint(leftKeys[i], rightKeys[j]);
		return scores;
	}

	private static String matchingKey(Model model) {
		String text = switch (model) {
			case MethodModel method -> method.getName() + method.getParameters() + method.getReturnType();
			case VariableModel variable -> variable.getName() + ":" + variable.getType();
			case ClassModel classModel -> classModel.getName();
			case ImportModel importModel -> importModel.getName();
			default -> model.toString();
		};
		return text.length() <= 256 ? text : text.substring(0, 128) + '\0' + text.substring(text.length() - 128);
	}

	private static double matchingHint(String left, String right) {
		if (left.equals(right)) return 1.0;
		int shorter = Math.min(left.length(), right.length()), longer = Math.max(left.length(), right.length());
		if (longer == 0) return 1.0;
		int prefix = 0;
		while (prefix < shorter && left.charAt(prefix) == right.charAt(prefix)) prefix++;
		int suffix = 0;
		while (suffix < shorter - prefix && left.charAt(left.length() - suffix - 1) == right.charAt(right.length() - suffix - 1)) suffix++;
		return 0.25 + (double) (prefix + suffix) / longer * 0.5 + (double) shorter / longer * 0.25;
	}

	private static double optionalSimilarity(Model a, Model b, Context ctx) {
		if (a == null && b == null) return 1.0;
		if (a == null || b == null) return 0.0;
		return visit(a, b, ctx);
	}

	private static double optionalStringSimilarity(String a, String b) {
		if (a == null && b == null) return 1.0;
		if (a == null || b == null) return 0.0;
		return relaxedNameMatch(a, b);
	}

	private static class Context {
		private int totalNodes = 0;
		private int matchingNodes = 0;
		private final List<String> mismatches = new ArrayList<>();

		void logMismatch(String fmt, Object... args) {
			if (mismatches.size() < 64)
				mismatches.add(String.format(fmt, args));
		}
	}
}
