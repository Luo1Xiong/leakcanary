package shark;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import kotlin.Pair;

import static shark.PrettyLogger.LogType.INVOKE_TRACK;
import static shark.PrettyLogger.LogType.JUST_LOG;
import static shark.PrettyLogger.LogType.TRACK_INSTANCE;
import static shark.PrettyLogger.LogType.TRACK_SINGLETON;

public class PrettyLogger {
	//   private static final Pair<String, String> clazzAndMethodPair = new Pair<>("", "");
//   private static final Pair<Pair<String, String>, StackTraceElement[]> StackTraceInfo = new Pair<>(clazzAndMethodPair, null);
	private static final String PRETTY_LOGGER = "PRETTY_LOGGER";

	/**
	 * 记录所有单利的创建次数
	 */
	private static HashMap<String, Integer> instanceCache = new HashMap<String, Integer>();

	enum LogType {
		JUST_LOG, INVOKE_TRACK, TRACK_SINGLETON, TRACK_INSTANCE
	}

	private static String identityHashCode(Object o) {
		return o + "@MEM:" + System.identityHashCode(o);
	}

//   private static final Pattern PATTERN_OPERATE_INCLUDE = Pattern.compile("^[-].*");

	public static void commonLog() {
		justLog(JUST_LOG, null, null);
	}

	public static void commonLog(String filter) {
		justLog(JUST_LOG, filter, null);
	}

	public static void commonLog(Object... objects) {
		justLog(JUST_LOG, null, buildFullContent(objects));
	}

	public static void commonLog(String filter, Object... objects) {
		justLog(JUST_LOG, filter, buildFullContent(objects));
	}

	public static void emptyInfoLog(Object... objects) {
		justLog(JUST_LOG, "", buildFullContent(objects));
	}

	public static void trackInstance(Object instance) {
		justLog(TRACK_INSTANCE, null, identityHashCode(instance));
	}

	/**
	 * 判断一个类是不是只创建过一次
	 */
	public static void trackSingleton(Object instance) {
		if (instanceCache.get(instance.getClass().getSimpleName()) != null) {
			System.out.println();
			System.out.println(TRACK_SINGLETON.name() + instance.getClass().getSimpleName() + " is created again");
		}
	}

	//region invokeTrack
	public static void invokeTrack() {
		justLog(INVOKE_TRACK, null, buildFullContent(getStackInfo().getSecond()));
	}

	/*
	 * 追踪一个/多个方法调用，精准过滤
	 * @param clazzAndMethods, combine clazz and multi methods with "#" to track these methods at the same time
	 *                         同时追踪多个方法调用（为了支持过滤），用“#”拼接
	 */
	public static void invokeTrack(String clazzAndMethods) {
		String[] pair = clazzAndMethods.trim().split("#", -1);
		if (pair.length < 2) {
			throw new IllegalStateException("params of invokeTrack must contain at least one \"#\"");
		}
		justTrack(pair[0], Arrays.copyOfRange(pair, 1, pair.length));
	}
	//endregion

	private static void justTrack(String clazz, String... methods) {
		Pair<Pair<String, String>, Pair<Integer, StackTraceElement[]>> stackInfo = getStackInfo();
		Pair<HashMap<String, FILTER_OPERATE>, List<String>> opsAndMethods = classifyOperatesAndMethods(
				methods);
		if (clazz.equals(stackInfo.getFirst().getFirst())) {
			boolean isEnableTrack = false;
			FILTER_OPERATE threadFilter = FILTER_OPERATE.JUST;
			if (opsAndMethods.getFirst().containsKey(Thread.currentThread().getName())) {
				threadFilter = opsAndMethods.getFirst().get(Thread.currentThread().getName());
			}
			if (threadFilter == FILTER_OPERATE.THREAD_EXCLUDE) {
				return;
			}
			for (String method : opsAndMethods.getSecond()) {
				if (method.equals(stackInfo.getFirst().getSecond())) {
					isEnableTrack = true;
					break;
				}
			}
			if (isEnableTrack) {
				StringBuilder builder = new StringBuilder();
				builder.append(INVOKE_TRACK).append(" ▶▶ ").append(clazz);
				for (String method : opsAndMethods.getSecond()) {
					builder.append("#").append(method);
				}
				builder.append("\n").append("◉ Thread : ").append(Thread.currentThread().getName())
						.append("\n");
				for (int i = stackInfo.getSecond().getFirst(); i < stackInfo.getSecond().getSecond().length; i++) {
					builder
							.append(i == stackInfo.getSecond().getFirst() ? "◉ Trace  : \n           " : "           ")
							.append(stackInfo.getSecond().getSecond()[i].toString()).append("\n");
				}
				// TODO: 21-8-6  
//            System.out.println(INVOKE_TRACK, builder.toString());
			}
		}
	}

	/*
	 * ↳ ➤ ● ❏ ▶ ◉ ↴
	 */
	private static void justLog(LogType logType, String filter, String fullContent) {
		Pair<String, String> clazzAndMethod = getStackInfo().getFirst();
		StringBuilder builder = new StringBuilder();
		builder.append("\r\t").append("\n");
		builder.append("◉ Thread : ").append(Thread.currentThread().getName()).append("\n");
		builder.append("◉ Class  : ").append(clazzAndMethod.getFirst()).append("\n");
		builder.append("◉ Method : ").append(clazzAndMethod.getSecond()).append("\n");
		if (fullContent != null && fullContent.length() > 0) {
			builder.append("◉ Content: ").append("\n");
			builder.append(fullContent).append("\n");
		}
		System.out.println(buildFullTag(logType, filter) + builder.toString());
	}

	/*
	 *                                                                                            ▶ justLog   ▶ commonLog
	 * dalvik.system.VMStack.getThreadStackTrace ▶ java.lang.Thread.getStackTrace ▶ getStackInfo                           ▶ "realClassAndMethod"
	 *                                                                                            ▶ justTrack ▶ invokeTrack
	 *
	 * @return Pair<clazz, method> and Pair<lineStartPrint, stackTraceElements>
	 */
	private static Pair<Pair<String, String>, Pair<Integer, StackTraceElement[]>> getStackInfo() {
		System.out.println(PRETTY_LOGGER + "▶▶");//修复打印多个相同的Log不显示完全
		StackTraceElement[] elementArray = Thread.currentThread().getStackTrace();
		int lineIndexBeforePrint = 0;
		String clazz = "", method = "";
		/*
		 * 0:dalvik.system.VMStack.getThreadStackTrace
		 * 1:java.lang.Thread.getStackTrace
		 * 2:maybe: PrettyLogger.getStackInfo  ▶▶ this
		 */
		for (int i = 2; i < elementArray.length; i++) {
			//exclude stack info inside of PrettyLogger
			if (!elementArray[i].toString().contains(PrettyLogger.class.getSimpleName())) {
				clazz = elementArray[i].getClassName();
				method = elementArray[i].getMethodName();
				String[] clazzPaths = clazz.trim().split("\\.", -1);
				clazz = clazzPaths[clazzPaths.length - 1];
				break;
			} else {
				lineIndexBeforePrint = i; //record which line before print
			}
		}
		return new Pair<>(new Pair<>(clazz, method),
				new Pair<>(++lineIndexBeforePrint, elementArray));
	}

	/**
	 * @param operatesAndMethods: @main、-main、@xxx、-xxx、etc. "-" means {@link FILTER_OPERATE#THREAD_EXCLUDE} and "@" means {@link FILTER_OPERATE#JUST}... and methods included too.
	 */
	private static Pair<HashMap<String, FILTER_OPERATE>, List<String>> classifyOperatesAndMethods(
			String... operatesAndMethods) {
		HashMap<String, FILTER_OPERATE> ops = new HashMap<>();
		List<String> methods = new ArrayList<>();
		for (String opOrMethod : operatesAndMethods) {
			opOrMethod = opOrMethod.trim();
			if (opOrMethod.startsWith("@")) {
				ops.put(opOrMethod.substring(1), FILTER_OPERATE.JUST);
			} else if (opOrMethod.startsWith("-")) {
				ops.put(opOrMethod.substring(1), FILTER_OPERATE.THREAD_EXCLUDE);
			} else {
				methods.add(opOrMethod);
			}
		}
		return new Pair<>(ops, methods);
	}

	private static String buildFullTag(LogType logType, String filter) {
		if (filter != null && filter.length() > 0) {
			return PRETTY_LOGGER + " ▶▶ " + logType.name();
		} else {
			return PRETTY_LOGGER + " ▶▶ " + logType.name() + " ▶▶ " + filter;
		}
	}

	private static String buildFullContent(String content) {
		return "           ● " + content;
	}

	private static String buildFullContent(Pair<Integer, StackTraceElement[]> pair) {
		StringBuilder builder = new StringBuilder();
		for (int i = pair.getFirst(); i < pair.getSecond().length; i++) {
			builder.append("           ● ").append(pair.getSecond()[i].toString()).append("\n");
		}
		return builder.toString();
	}

	private static String buildFullContent(Object... objects) {
		StringBuilder builder = new StringBuilder();
		for (Object object : objects) {
			builder.append("           ● ");
			if (object instanceof String) {
				builder.append(object);
			} else {
				builder.append(identityHashCode(object));
			}
			builder.append("\n");
		}
		return builder.toString();
	}

	/*
	 * 用于过滤：线程名称、
	 */
	enum FILTER_OPERATE {
		JUST,    // "@"
		THREAD_EXCLUDE, // "-"
	}
}
