package org.geogebra.common.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;

import org.geogebra.common.kernel.Construction;
import org.geogebra.common.kernel.Macro;
import org.geogebra.common.kernel.StringTemplate;
import org.geogebra.common.kernel.algos.AlgoMacro;
import org.geogebra.common.kernel.arithmetic.ExpressionNodeEvaluator;
import org.geogebra.common.kernel.arithmetic.ExpressionValue;
import org.geogebra.common.kernel.arithmetic.Inspecting;
import org.geogebra.common.kernel.geos.GeoElement;
import org.geogebra.common.kernel.geos.GeoPoint;
import org.geogebra.common.kernel.geos.Test;
import org.geogebra.common.main.App;

/**
 * @author Christoph
 * 
 */
public class GeoAssignment implements Assignment {

	/**
	 * The Result of the Assignment
	 */
	public static enum Result {
		/**
		 * The assignment is CORRECT
		 */
		CORRECT,
		/**
		 * The assignment is WRONG and we can't tell why
		 */
		WRONG,
		/**
		 * There are not enough input geos, so we cannot check
		 */
		NOT_ENOUGH_INPUTS,
		/**
		 * We have enough input geos, but one or more are of the wrong type
		 */
		WRONG_INPUT_TYPES,
		/**
		 * There is no output geo matching our macro
		 */
		WRONG_OUTPUT_TYPE,
		/**
		 * The assignment was correct in the first place but wrong after
		 * randomization
		 */
		WRONG_AFTER_RANDOMIZE,
		/**
		 * The assignment could not be checked
		 */
		UNKNOWN,
	}

	/**
	 * Possible values for CheckOperations
	 */
	public final static String[] CHECK_OPERATIONS = { "==", "AreEqual",
			"AreCongruent" };

	private String checkOp;

	private Inspecting geoInspector;

	private Macro macro;

	private HashMap<Result, Float> fractionForResult;
	/* The hints displayed to the Student */
	private HashMap<Result, String> hintForResult;

	private GeoElement[] solutionObjects;

	private Result res;

	private int callsToEqual, callsToCheckTypes;

	private Test[] inputTypes;
	private HashSet<Test> uniqueInputTypes;
	private TreeSet<GeoElement> randomizeablePredecessors;

	private Construction cons;



	/**
	 * @param macro
	 *            the macro (user defined tool) corresponding to the assignment
	 */
	public GeoAssignment(Macro macro) {
		this.macro = macro;
		inputTypes = macro.getInputTypes();

		uniqueInputTypes = new HashSet<Test>(Arrays.asList(inputTypes));
		randomizeablePredecessors = new TreeSet<GeoElement>();

		fractionForResult = new HashMap<Result, Float>();
		hintForResult = new HashMap<Result, String>();
		checkOp = "AreEqual";

		geoInspector = new Inspecting() {

			public boolean check(ExpressionValue v) {
				return ((GeoElement) v).labelSet
						&& uniqueInputTypes.contains(Test.getSpecificTest(v));
			}

		};

		res = Result.UNKNOWN;
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#checkAssignment(org.geogebra.common.kernel.Construction)
	 */
	@Override
	public Result checkAssignment(Construction construction) {
		this.cons = construction;
		res = Result.UNKNOWN;
		callsToEqual = 0;
		callsToCheckTypes = 0;
		boolean oldSilentMode = cons.getKernel().isSilentMode();
		cons.getKernel().setSilentMode(true);

		TreeSet<GeoElement> possibleOutputGeos = new TreeSet<GeoElement>();

		// find all possible inputgeos and all outputgeos that match the type of
		// the macro
		TreeSet<GeoElement> sortedSet = cons.getGeoSetNameDescriptionOrder();
		Iterator<GeoElement> it = sortedSet.iterator();
		while (it.hasNext()) {
			GeoElement geo = it.next();
			TreeSet<GeoElement> allPredecessors = geo.getAllPredecessors();
			if (!allPredecessors.isEmpty()) {
				for (GeoElement macroOut : macro.getMacroOutput()) {
					if (macroOut.getClass().equals(geo.getClass())) {
						possibleOutputGeos.add(geo);
					}
				}
			}
		}
		if (macro.getMacroOutput().length > possibleOutputGeos.size()) {
			res = Result.WRONG_OUTPUT_TYPE;
		} else {
			checkCorrectness(possibleOutputGeos);
		}
		App.debug("Checking on " + macro.getToolName()
				+ " completed. Comparisons of Objects: " + callsToEqual);
		App.debug("Checking on " + macro.getToolName()
				+ " completed. Checked types of Objects: " + callsToCheckTypes);
		cons.getKernel().setSilentMode(oldSilentMode);

		return res;
	}

	private void checkCorrectness(TreeSet<GeoElement> possibleOutputGeos) {

		PermutationOfGeOElementsUtil outputPermutationUtil = new PermutationOfGeOElementsUtil(
				possibleOutputGeos.toArray(new GeoElement[0]),
				macro.getMacroOutput().length);
		GeoElement[] possibleOutputPermutation = outputPermutationUtil.next();

		TreeSet<Result> partRes = new TreeSet<Result>();
		while (possibleOutputPermutation != null && res != Result.CORRECT) {
			TreeSet<GeoElement> possibleInputGeos = getAllPredecessors(
					possibleOutputPermutation, geoInspector);
			if (possibleInputGeos.size() < macro.getInputTypes().length) {
				res = Result.NOT_ENOUGH_INPUTS;
			} else {
				checkPermutationsOfInputs(possibleOutputPermutation, partRes,
						possibleInputGeos);
			}
			possibleOutputPermutation = outputPermutationUtil.next();
		}
	}

	private void checkPermutationsOfInputs(
			GeoElement[] possibleOutputPermutation, TreeSet<Result> partRes,
			TreeSet<GeoElement> possibleInputGeos) {
		boolean isTypeCheckNeeded = uniqueInputTypes.size() > 1;
		GeoElement[] input;
		PermutationOfGeOElementsUtil inputPermutationUtil = new PermutationOfGeOElementsUtil(
				possibleInputGeos.toArray(new GeoElement[0]),
				macro.getInputTypes().length);

		input = inputPermutationUtil.next();
		boolean solutionFound = false;
		while (input != null && !solutionFound) {
			partRes.clear();
			if (!isTypeCheckNeeded || areTypesOK(input)) {
				AlgoMacro algoMacro = new AlgoMacro(cons, null, macro, input);
				GeoElement[] macroOutput = algoMacro.getOutput();
				for (int i = 0; i < possibleOutputPermutation.length
						&& (!partRes.contains(Result.WRONG)); i++) {
					checkEqualityOfGeos(input, macroOutput[i],
							possibleOutputPermutation, i, partRes);
				}
				algoMacro.remove();
				solutionFound = !partRes.contains(Result.WRONG)
						&& !partRes.contains(Result.WRONG_AFTER_RANDOMIZE)
						&& partRes.contains(Result.CORRECT);
			} else if (res != Result.WRONG_AFTER_RANDOMIZE
					&& res != Result.WRONG) {
				res = Result.WRONG_INPUT_TYPES;
			}
			if (partRes.contains(Result.WRONG)
					&& res != Result.WRONG_AFTER_RANDOMIZE) {
				res = Result.WRONG;
			} else if (partRes.contains(Result.WRONG_AFTER_RANDOMIZE)) {
				res = Result.WRONG_AFTER_RANDOMIZE;
				App.debug("Objects wrong after Randomize: "
						+ toString(possibleOutputPermutation));
				App.debug("Objects used as inputs: " + toString(input));
			} else if (partRes.contains(Result.CORRECT)) {
				res = Result.CORRECT;
				solutionObjects = possibleOutputPermutation;
				App.debug("Objects found to be the Solution: "
						+ toString(solutionObjects));
				App.debug("Objects used as inputs: " + toString(input));
			}
			input = inputPermutationUtil.next();

		}
	}


	private void checkEqualityOfGeos(GeoElement[] input,
			GeoElement macroOutput, GeoElement possibleOutput[], int i,
			TreeSet<Result> partRes) {
		// TODO Check if we really need to call adjustMoveableOutputs with all
		// possibleOutputs ie.the array
		boolean mayAdjustMoveableOutputs = adjustMoveableOutputs(macroOutput,
				possibleOutput);
		if (checkOp.equals("AreEqual")) {
			partRes.add(macroOutput.isEqual(possibleOutput[i]) ? Result.CORRECT
					: Result.WRONG);
		} else if (checkOp.equals("==")) {
			partRes.add(ExpressionNodeEvaluator.evalEquals(macro.getKernel(),
					macroOutput, possibleOutput[i]).getBoolean() ? Result.CORRECT
					: Result.WRONG);
		} else if (checkOp.equals("AreCongruent")) {
			partRes.add(boolVal(macroOutput.isCongruent(possibleOutput[i])) ? Result.CORRECT
					: Result.WRONG);
		}
		callsToEqual++;
		int j = 0;
		if (partRes.contains(Result.CORRECT)) {
			while (j < input.length
					&& !partRes.contains(Result.WRONG_AFTER_RANDOMIZE)) {
				if (input[j].isRandomizable()) {
					mayAdjustMoveableOutputs = doProbabilisticChecking(
							input[j], macroOutput, possibleOutput, i, partRes,
							mayAdjustMoveableOutputs);
				} else {
					input[j].addRandomizablePredecessorsToSet(randomizeablePredecessors);
					for (int k = 0; k < randomizeablePredecessors.size()
							&& !partRes.contains(Result.WRONG_AFTER_RANDOMIZE); k++) {
						mayAdjustMoveableOutputs = doProbabilisticChecking(
								randomizeablePredecessors.pollFirst(),
								macroOutput, possibleOutput, i, partRes,
								mayAdjustMoveableOutputs);
					}
				}
				j++;
			}
		}
	}

	private static boolean boolVal(Boolean congruent) {
		return congruent != null && congruent;
	}

	private boolean doProbabilisticChecking(GeoElement geoToRandomize,
			GeoElement macroOutput, GeoElement[] possibleOutput, int i,
			TreeSet<Result> partRes, boolean mayAdjustMoveableOutputs) {
		boolean mayAdjustMoveableOutputsL = mayAdjustMoveableOutputs;
		GeoElement saveInput;
		saveInput = geoToRandomize.copy();
		geoToRandomize.randomizeForProbabilisticChecking();
		geoToRandomize.updateCascade();
		if (mayAdjustMoveableOutputs) {
			mayAdjustMoveableOutputsL = adjustMoveableOutputs(macroOutput,
					possibleOutput);
		}
		// partRes.add(algoEqual.getResult().getBoolean() ?
		// Result.CORRECT
		// : Result.WRONG_AFTER_RANDOMIZE);
		if (checkOp.equals("AreEqual")) {
			partRes.add(macroOutput.isEqual(possibleOutput[i]) ? Result.CORRECT
					: Result.WRONG_AFTER_RANDOMIZE);
		} else if (checkOp.equals("==")) {
			partRes.add(ExpressionNodeEvaluator.evalEquals(macro.getKernel(),
					macroOutput, possibleOutput[i]).getBoolean() ? Result.CORRECT
					: Result.WRONG_AFTER_RANDOMIZE);
		} else if (checkOp.equals("AreCongruent")) {
			partRes.add(macroOutput.isCongruent(possibleOutput[i]) ? Result.CORRECT
					: Result.WRONG_AFTER_RANDOMIZE);
		}
		callsToEqual++;
		geoToRandomize.set(saveInput);
		geoToRandomize.updateCascade();
		return mayAdjustMoveableOutputsL;
	}

	/**
	 * If some macro outputs are moveable (eg. point on path), push them close
	 * to the corresponding possible outputs (within given path/region
	 * constraint)
	 * 
	 * @param macroOutput
	 *            sample macro output
	 * @param possibleOutput
	 *            possible outputs
	 * @return whether an output was changeable
	 */
	private static boolean adjustMoveableOutputs(GeoElement macroOutput,
			GeoElement[] possibleOutput) {
		boolean ret = false;
		AlgoMacro algo = (AlgoMacro) macroOutput.getParentAlgorithm();
		int size = algo.getOutputLength();
		for (int i = 0; i < size; i++) {
			if (algo.isChangeable(algo.getOutput(i))
					&& possibleOutput[i] instanceof GeoPoint) {
				GeoPoint pt = (GeoPoint) possibleOutput[i];
				algo.setCoords((GeoPoint) algo.getOutput(i), pt.getX(),
						pt.getY(), pt.getZ());
				ret = true;
			}
		}
		return ret;
	}

	private static TreeSet<GeoElement> getAllPredecessors(
			GeoElement[] possibleOutputPermutation, Inspecting geoInspector) {

		TreeSet<GeoElement> possibleInputGeos = new TreeSet<GeoElement>();
		for (int i = 0; i < possibleOutputPermutation.length; i++) {
			possibleOutputPermutation[i].addPredecessorsToSet(
					possibleInputGeos, geoInspector);
		}
		for (int i = 0; i < possibleOutputPermutation.length; i++) {
			possibleInputGeos.remove(possibleOutputPermutation[i]);
		}

		return possibleInputGeos;
	}

	private boolean areTypesOK(GeoElement[] input) {
		boolean typesOK = true; // we assume that types are OK

		int k = 0;
		while (k < input.length && typesOK) {
			callsToCheckTypes++;
			if (inputTypes[k].check(input[k])) {
				typesOK = true;
			} else {
				typesOK = false;
			}
			k++;
		}
		return typesOK;
	}

	private static String toString(GeoElement[] elements) {
		String solObj = "";
		for (GeoElement g : elements) {
			if (!solObj.isEmpty()) {
				solObj += ", ";
			}
			solObj += g.toString(StringTemplate.defaultTemplate);

		}
		return solObj;
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#getFraction()
	 */
	@Override
	public float getFraction() {
		float fraction = 0f;
		if (fractionForResult.containsKey(res)) {
			fraction = fractionForResult.get(res);
		} else if (res == Result.CORRECT) {
			fraction = 1.0f;
		}
		return fraction;
	}

	/**
	 * @param result
	 *            the result for which the fraction should be set
	 * @param f
	 *            the fraction in the interval [-1,1] which should be used for
	 *            the result (will do nothing if fraction is not in [-1,1])
	 */
	public void setFractionForResult(Result result, float f) {
		if (-1 <= f && f <= 1) {
			fractionForResult.put(result, f);
		}
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#getFractionForResult(org.geogebra.common.util.GeoAssignment.Result)
	 */
	@Override
	public float getFractionForResult(Result result) {
		float frac = 0f;
		if (fractionForResult.containsKey(result)) {
			frac = fractionForResult.get(result);
		} else if (result == Result.CORRECT) {
			frac = 1.0f;
		}
		return frac;
	}

	/**
	 * @return the icon file name of the user defined tool corresponding to this
	 *         assignment
	 */
	public String getIconFileName() {
		return macro.getIconFileName();
	}

	/**
	 * @return the Name of the Tool corresponding to this Assignment
	 */
	public String getToolName() {
		return macro.getToolName();
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#getHint()
	 */
	@Override
	public String getHint() {
		return hintForResult.get(res);
	}

	/**
	 * Sets the Hint for a particular Result.
	 * 
	 * @param res
	 *            the {@link Result}
	 * @param hint
	 *            the hint which should be shown to the student in case of the
	 *            {@link Result} res
	 */
	public void setHintForResult(Result res, String hint) {
		this.hintForResult.put(res, hint);
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#getResult()
	 */
	@Override
	public Result getResult() {
		return res;
	}

	/**
	 * @param result
	 *            the Result for which the hint should be returned
	 * @return hint corresponding to result
	 */
	public String getHintForResult(Result result) {
		String hint = "";
		if (hintForResult.containsKey(result)) {
			hint = hintForResult.get(result);
		}
		return hint;
	}

	/**
	 * @return the user defined tool corresponding to the assignment
	 */
	public Macro getTool() {
		return macro;
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#hasHint()
	 */
	@Override
	public boolean hasHint() {
		return !hintForResult.isEmpty();
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#hasFraction()
	 */
	@Override
	public boolean hasFraction() {
		return !fractionForResult.isEmpty();
	}

	/* (non-Javadoc)
	 * @see org.geogebra.common.util.Assignment#getAssignmentXML()
	 */
	@Override
	public String getAssignmentXML() {
		StringBuilder sb = new StringBuilder();
		sb.append("\t<assignment toolName=\"");
		StringUtil.encodeXML(sb, macro.getToolName());
		sb.append("\" commandName=\"");
		StringUtil.encodeXML(sb, macro.getCommandName());
		sb.append("\" checkOperation=\"");
		StringUtil.encodeXML(sb, getCheckOperation());
		sb.append("\">\n");

		if (hasHint() || hasFraction()) {
			for (Result res1 : Result.values()) {
				String hint = hintForResult.get(res1);
				Float fraction = fractionForResult.get(res1);
				if (hint != null && !hint.isEmpty() || fraction != null) {
					sb.append("\t\t<result name=\"");
					StringUtil.encodeXML(sb, res1.toString());
					sb.append("\" ");
					if (hint != null && !hint.isEmpty()) {
						sb.append("hint=\"");
						StringUtil.encodeXML(sb, hint);
						sb.append("\" ");
					}
					if (fraction != null) {
						sb.append("fraction=\"");
						sb.append(fraction.floatValue());
						sb.append("\" ");
					}
					sb.append("/>\n");
				}
			}
		}
		sb.append("\t</assignment>\n");
		return sb.toString();
	}

	/**
	 * @param newTool
	 *            the Macro which should be used to checking
	 */
	public void setMacro(Macro newTool) {
		macro = newTool;
	}

	public String getCheckOperation() {
		return checkOp;
	}

	public void setCheckOperation(String checkOp) {
		this.checkOp = checkOp;
	}
}

// Eyal Schneider
// http://stackoverflow.com/a/2799190
/**
 * Utility Class to permute the array of GeoElements
 * 
 * @author Eyal Schneider, http://stackoverflow.com/a/2799190
 * @author Adaption: Christoph Stadlbauer
 */
class PermutationOfGeOElementsUtil {
	private GeoElement[] arr;
	private int[] permSwappings;

	/**
	 * @param arr
	 *            the Array with the Elements to be permuted
	 */
	public PermutationOfGeOElementsUtil(GeoElement[] arr) {
		this(arr, arr.length);
	}

	/**
	 * @param arr
	 *            the Array with the Elements to be permuted
	 * @param permSize
	 *            the Elements k < arr.length of the array you need to permute
	 */
	public PermutationOfGeOElementsUtil(GeoElement[] arr, int permSize) {

		// this.arr = arr.clone();
		this.arr = new GeoElement[arr.length];
		System.arraycopy(arr, 0, this.arr, 0, arr.length);
		this.permSwappings = new int[permSize];
		for (int i = 0; i < permSwappings.length; i++) {
			permSwappings[i] = i;
		}
	}

	/**
	 * @return the next permutation of the array if exists, null otherwise
	 */
	public GeoElement[] next() {
		if (arr == null) {
			return null;
		}

		GeoElement[] res = new GeoElement[permSwappings.length];
		System.arraycopy(arr, 0, res, 0, permSwappings.length);
		// GeoElement[] res = Arrays.copyOf(arr, permSwappings.length);

		// Prepare next
		int i = permSwappings.length - 1;
		while (i >= 0 && permSwappings[i] == arr.length - 1) {
			swap(i, permSwappings[i]); // Undo the swap represented by
										// permSwappings[i]
			permSwappings[i] = i;
			i--;
		}

		if (i < 0) {
			arr = null;
		} else {
			int prev = permSwappings[i];
			swap(i, prev);
			int next = prev + 1;
			permSwappings[i] = next;
			swap(i, next);
		}

		return res;
	}

	private void swap(int i, int j) {
		GeoElement tmp = arr[i];
		arr[i] = arr[j];
		arr[j] = tmp;
	}

}