package ga.uuid.app;

import static ga.uuid.app.Const.delay;
import static ga.uuid.app.Const.isEmpty;
import static ga.uuid.app.Const.renderTable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class TinyDownloader {
	
	private static final ExecutorService pool = Executors.newFixedThreadPool(Const.THREAD_SIZE);
	// 等待添加的任务队列
	private static final BlockingDeque<Runnable> taskQueue = new LinkedBlockingDeque<>();
	// 正在下载的任务
	private static final FixedList<DownloadTask> downloadingList = new FixedList<>(Const.THREAD_SIZE);
	
	// 统计
	private static LongAdder successCount = new LongAdder();
	private static LongAdder failCount = new LongAdder();
	private static LongAdder skipCount = new LongAdder();
	private static final Map<State, LongAdder> counterMap = new HashMap<>();
	
	private static transient boolean start = true;
	
	// 实时下载总字节数统计，为了性能不使用同步，允许些许误差 （统计可能比实际小）
	private static transient int allBytes = 0;
	private static transient int bytesPerSecond = 0;
	
	static {
		// 统计映射
		counterMap.put(State.SUCCESS, successCount);
		counterMap.put(State.FAIL, failCount);
		counterMap.put(State.EXISTED, skipCount);
		
		// 下载任务队列监控线程
		new Thread(() -> {
			while (start) {
				try {
					Runnable task = taskQueue.take();
					pool.execute(task);
					delay(100); // 任务之间插入延迟避免同源任务导致503
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			if (!pool.isShutdown()) pool.shutdown();
		}, "monitor thread").start();
		
		// 统计线程
		Thread statisticsThread = new Thread(() -> {
			Consumer<StringBuilder> consumer = null;
			if (Const.OUTPUT_MODE == 1) {
				consumer = s -> {
					// 附加实时下载任务数据
					s.append("\n").append(renderTable(downloadingList));
					// 输出实时下载任务面板
					Console.print(s.toString());
				};
			} else consumer = System.out::println;
			
			// 默认console恢复标识
			boolean recoveredConsole = false;
			ThreadPoolExecutor _pool = (ThreadPoolExecutor) pool;
			// 统计总览
			for (;;) {
				delay(Const.OUTPUT_INTERVAL);
				int activeCount = _pool.getActiveCount();
				// 当前有下载任务才进行输出
				if (activeCount > 0) {
					recoveredConsole = false;
					StringBuilder sb = statisticsInfo();
					sb.append("  pending: ").append(_pool.getTaskCount() - _pool.getCompletedTaskCount());
					output(sb, consumer);
				} else {
					if (!recoveredConsole) {
						// 向默认console输出统计信息
						System.out.println(statisticsInfo());
						Console.recover();
					}
					recoveredConsole = true;
				}
			}
		}, "statistics_thread");
		statisticsThread.setDaemon(true);
		statisticsThread.start();
		
		// 下载总速度监控线程
		Thread networkMonitorThread = new Thread(() -> {
			for (;;) {
				delay(1000);
				bytesPerSecond = allBytes;
				allBytes = 0;
			}
		}, "network_monitor_thread");
		networkMonitorThread.setDaemon(true);
		networkMonitorThread.start();
	}
	
	/**
	 * 确认下载任务，确认后不能再通过add方法添加下载任务
	 */
	public static void ensure() {
		if (start) {
			// 如果下载任务队列中不为空的话，等待消耗完毕后才设置状态
			while (!taskQueue.isEmpty()) {
				delay(100);
			}
			start = false;
			taskQueue.add(() -> {});
		}
	}
	
	/**
	 * 添加下载任务
	 * @param url 远程url地址
	 * @param path 保存的目录
	 */
	public static void add(String url, String path) {
		add(url, path, null);
	}
	
	/**
	 * 添加下载任务
	 * @param url 远程url地址
	 * @param path 保存的目录
	 * @param filename 保存的文件名
	 * @return
	 */
	public static void add(String url, String path, String filename) {
		if (start) {
			DownloadTask task = new DownloadTask(url, path, filename);
			taskQueue.add(task);
		} else {
			// TODO else 任务队列已关闭
			System.out.println("the pool already closed.");
		}
	}
	
	/**
	 * 以阻塞方式直接下载文件
	 * @param url 远程url地址
	 * @param path 保存的目录
	 * @return
	 */
	public static State download(String url, String path) {
		return download(url, path, null);
	}
	
	/**
	 * 以阻塞方式直接下载文件
	 * @param url 远程url地址
	 * @param path 保存的目录
	 * @param filename 保存的文件名
	 * @return
	 */
	public static State download(String url, String path, String filename) {
		System.out.println("downloading " + url + " --> " + path);
		State state = download(new DownloadTask(url, path, filename));
		System.out.println(state);
		return state;
	}
	
	/**
	 * 下载文件简单实现 ，buff中直接保存文件所有字节，下载完后写入磁盘
	 * 不支持断点续传，不推荐大文件下载
	 * @param task
	 * @return
	 */
	static State download(DownloadTask task) {
		Objects.requireNonNull(task);
		
		URL url = null;
		HttpURLConnection conn = null;
		String destFile = "";
		
		try {
			url = new URL(task.getUrl());
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(30000);  
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/43.0.2357.134 Safari/" + Math.random());
			// 下载任务中的文件名未指定的话，那么通过 header 获取，如果 header 中未获取成功那么通过 url 截取
			if (isEmpty(task.getFilename())) {
				Map<String, List<String>> headers = conn.getHeaderFields();
				Optional<String> filenameOpt = parseFilename(headers);
				String filename = filenameOpt.orElseGet(() -> {
					String _url = task.getUrl();
					return _url.substring(_url.lastIndexOf("/")+1, _url.length());
				});
				task.setFilename(filename);
			}
			// 下载目录如果不存在，那么创建该目录
			Path directory = Paths.get(task.getPath());
			if (Files.notExists(directory)) {
				synchronized (TinyDownloader.class) {
					if (Files.notExists(directory)) Files.createDirectories(directory);
				}
			}
			
			destFile = String.join("/", task.getPath(), task.getFilename());
			// 检查当前文件是否存在，如果存在那么跳过
			Path path = Paths.get(destFile);
			if (Files.exists(path)) {
				if (Files.size(path) > 0) return State.EXISTED;
			}
		} catch (IOException e) {
			// TODO
			e.printStackTrace();
		}
		
		// 下载文件的简单实现
		try (	InputStream in = new BufferedInputStream(conn.getInputStream());
				FileOutputStream fos = new FileOutputStream(destFile);
				OutputStream out = new BufferedOutputStream(fos);) {
			task.setFilesize(conn.getContentLength());
			if (task.getFilesize() < 1) {
				// TODO
				return State.FAIL;
			}
			byte[] binary = new byte[task.getFilesize()];
			byte[] buff = new byte[65536];
			int len = -1;
			int index = 0;
			while ((len = in.read(buff)) != -1) {
				System.arraycopy(buff, 0, binary, index, len);
				index += len;
				task.setReceivedSize(index);
//				downloadingList.stream().map(DownloadTask::getReceivedSize).
				allBytes += len; // 用于统计全局速度
			}
			out.write(binary);
			out.flush();
		} catch (IOException e) {
			// TODO
			e.printStackTrace();
			return State.FAIL;
		}
		return State.SUCCESS;
	}
	
	/**
	 * 通过headers解析出文件名
	 * @param map the headers map.
	 * @return
	 */
	private static final Optional<String> parseFilename(Map<String, List<String>> map) {
		String filename = null;
		try {
			String disposition = map.get("Content-Disposition").get(0);
			Pattern pattern = Pattern.compile("(filename\\*?)=([^;]+)");
			Matcher matcher = pattern.matcher(disposition);
			while (matcher.find()) {
				filename = matcher.group(2);
				if ("filename*".equals(matcher.group(1))) {
					break;
				}
			}
		} catch (NullPointerException e) {}
		if (isEmpty(filename)) filename = null; 
		return Optional.ofNullable(filename);
	}
	
	/**
	 * 加入到正在下载列表
	 * @param task
	 * @return
	 */
	synchronized static boolean downloading_add(DownloadTask task) {
		return downloadingList.add(task);
	}
	
	/**
	 * 将该任务移除正在下载列表
	 * @param task
	 * @return
	 */
	synchronized static boolean downloading_remove(DownloadTask task) {
		return downloadingList.remove(task);
	}
	
	/**
	 * 统计下载成功、失败、跳过等数量
	 * @param state
	 */
	static void statistcsCount(State state) {
		counterMap.get(state).increment();
	}
	
	/**
	 * 统计信息
	 * @return
	 */
	private static StringBuilder statisticsInfo() {
		StringBuilder sb = new StringBuilder("\n speed: ");
		sb.append(Const.speed(bytesPerSecond));
		sb.append("  success: ").append(successCount);
		sb.append("  fail: ").append(failCount);
		sb.append("  skip: ").append(skipCount);
		return sb;
	}
	
	private static <T> void output(T t, Consumer<T> consumer) {
		consumer.accept(t);
	}
	
	/**
	 * 任务下载状态
	 * @author abeholder
	 *
	 */
	static enum State {
		SUCCESS, FAIL, EXISTED;
	}
}