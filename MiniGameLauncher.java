import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MiniGameLauncher {
		private static final String HOST = "127.0.0.1";
		private static final String INDEX_HTML = "index.html";
		private static final String APP_JS = "app.js";
		private static final String STYLES_CSS = "styles.css";

		public static void main(String[] args) throws Exception {
				Path runtimeRoot = Files.createTempDirectory("mini-game-launcher-");
				Path webRoot = runtimeRoot.resolve("web");
				Path miniGameDir = webRoot.resolve("MiniGame");

				Files.createDirectories(webRoot);
				Files.createDirectories(miniGameDir);

				writeWebFiles(webRoot);
				writeSampleGame(miniGameDir);

				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
						try {
								deleteRecursively(runtimeRoot);
						} catch (IOException ignored) {
								// Cleanup best-effort.
						}
						cleanupLauncherClassFiles();
				}));

				HttpServer server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
				int port = server.getAddress().getPort();

				server.createContext("/", new StaticFileHandler(webRoot));
				server.createContext("/api/games", new GameApiHandler(miniGameDir));
				server.createContext("/api/run", new RunApiHandler(miniGameDir));
				server.setExecutor(Executors.newCachedThreadPool());
				server.start();

				String url = "http://" + HOST + ":" + port + "/";
				System.out.println("MiniGame Launcher started: " + url);
				System.out.println("Ctrl+C で終了すると、一時ファイルは自動削除されます。");

				if (Desktop.isDesktopSupported()) {
						Desktop.getDesktop().browse(URI.create(url));
				} else {
						System.out.println("ブラウザを自動起動できない環境です。上記URLを手動で開いてください。");
				}
		}

		private static void writeWebFiles(Path webRoot) throws IOException {
				Files.writeString(webRoot.resolve(INDEX_HTML), htmlContent(), StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				Files.writeString(webRoot.resolve(APP_JS), jsContent(), StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				Files.writeString(webRoot.resolve(STYLES_CSS), cssContent(), StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}

		private static void writeSampleGame(Path miniGameDir) throws IOException {
				String sampleJava = """
								public class sample {
										public static void main(String[] args) {
												System.out.println("Sample Mini Game");
										}
								}
								""";

				String tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAoMBgD9n1V8AAAAASUVORK5CYII=";

				Path javaPath = miniGameDir.resolve("sample.java");
				Path imagePath = miniGameDir.resolve("sample.png");
				Files.writeString(javaPath, sampleJava, StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				Files.write(imagePath, Base64.getDecoder().decode(tinyPng),
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}

		private static void deleteRecursively(Path root) throws IOException {
				if (!Files.exists(root)) {
						return;
				}
				try (var paths = Files.walk(root)) {
						paths.sorted(Comparator.reverseOrder()).forEach(path -> {
								try {
										Files.deleteIfExists(path);
								} catch (IOException ignored) {
										// Cleanup best-effort.
								}
						});
				}
		}

		private static void cleanupLauncherClassFiles() {
				try {
						Path classDir = getLauncherClassDirectory();
						if (classDir == null || !Files.isDirectory(classDir)) {
								return;
						}

						try (DirectoryStream<Path> stream = Files.newDirectoryStream(classDir, "MiniGameLauncher*.class")) {
								for (Path classFile : stream) {
										try {
												Files.deleteIfExists(classFile);
										} catch (IOException e) {
												classFile.toFile().deleteOnExit();
										}
								}
						}
				} catch (IOException ignored) {
						// Cleanup best-effort.
				}
		}

		private static Path getLauncherClassDirectory() {
				try {
						var source = MiniGameLauncher.class.getProtectionDomain().getCodeSource();
						if (source == null) {
								return null;
						}
						Path location = Path.of(source.getLocation().toURI());
						if (Files.isDirectory(location)) {
								return location;
						}
						return location.getParent();
				} catch (URISyntaxException e) {
						return null;
				}
		}

		private static String htmlContent() {
				return """
								<!doctype html>
								<html lang="ja">
								<head>
									<meta charset="utf-8" />
									<meta name="viewport" content="width=device-width, initial-scale=1" />
									<title>Mini Game Launcher</title>
									<link rel="stylesheet" href="/styles.css" />
								</head>
								<body>
									<main class="app-shell">
										<header class="header">
											<h1>Mini Game Launcher</h1>
										</header>

										<section id="player-view" class="panel">
											<div class="carousel-wrap">
												<button id="prev-btn" class="nav-btn" aria-label="前へ">◀</button>
												<div id="card" class="card">
													<img id="game-image" alt="Mini Game" />
													<div id="game-title" class="title"></div>
												</div>
												<button id="next-btn" class="nav-btn" aria-label="次へ">▶</button>
											</div>

											<p class="hint">画像をタップすると対応するJavaファイルを実行します</p>

											<dialog id="source-dialog">
												<h2 id="dialog-title"></h2>
												<pre id="source-code"></pre>
												<form method="dialog">
													<button>閉じる</button>
												</form>
											</dialog>
										</section>

										<section id="admin-view" class="panel hidden">
											<h2>管理画面</h2>
											<p>Javaファイルと画像をセットで登録します。保存先は MiniGame フォルダです。</p>

											<form id="upload-form" class="upload-form">
												<label>
													Javaファイル(.java)
													<input id="java-file" type="file" accept=".java" required />
												</label>
												<label>
													画像ファイル(.png/.jpg/.jpeg/.webp/.gif)
													<input id="image-file" type="file" accept="image/*" required />
												</label>
												<button type="submit">登録</button>
											</form>

											<div id="admin-message" class="message"></div>
											<button id="back-btn" class="secondary">プレイヤー画面に戻る</button>
										</section>
									</main>
									<script src="/app.js"></script>
								</body>
								</html>
								""";
		}

		private static String cssContent() {
				return """
								:root {
									--bg: #0e1726;
									--panel: #13213a;
									--text: #f6f8ff;
									--accent: #ffb703;
									--accent-2: #fb8500;
									--muted: #9cb1d5;
								}

								* { box-sizing: border-box; }

								body {
									margin: 0;
									min-height: 100vh;
									font-family: "Segoe UI", "Hiragino Kaku Gothic ProN", "Yu Gothic", sans-serif;
									color: var(--text);
									background:
										radial-gradient(circle at 20% 10%, rgba(251, 133, 0, 0.25), transparent 30%),
										radial-gradient(circle at 80% 90%, rgba(255, 183, 3, 0.2), transparent 35%),
										var(--bg);
								}

								.app-shell {
									width: min(980px, 100%);
									margin: 0 auto;
									padding: 24px 16px 40px;
								}

								.header h1 {
									margin: 0;
									font-size: clamp(1.8rem, 4vw, 3rem);
								}

								.header p {
									margin: 6px 0 0;
									color: var(--muted);
								}

								.panel {
									margin-top: 20px;
									background: linear-gradient(180deg, rgba(19, 33, 58, 0.92), rgba(12, 22, 40, 0.95));
									border: 1px solid rgba(255, 183, 3, 0.25);
									border-radius: 16px;
									padding: 18px;
									box-shadow: 0 16px 40px rgba(0, 0, 0, 0.3);
								}

								.hidden { display: none; }

								.carousel-wrap {
									display: grid;
									grid-template-columns: auto 1fr auto;
									align-items: center;
									gap: 12px;
								}

								.card {
									position: relative;
									overflow: hidden;
									border-radius: 14px;
									border: 1px solid rgba(255,255,255,0.15);
									min-height: 300px;
									background: rgba(255,255,255,0.03);
									cursor: pointer;
									touch-action: pan-y;
								}

								.card img {
									width: 100%;
									height: min(64vw, 460px);
									object-fit: cover;
									display: block;
								}

								.title {
									position: absolute;
									left: 10px;
									right: 10px;
									bottom: 10px;
									padding: 10px 12px;
									border-radius: 10px;
									background: rgba(0,0,0,0.55);
									font-weight: 600;
								}

								.nav-btn {
									border: none;
									background: var(--accent);
									color: #1f1f1f;
									width: 42px;
									height: 42px;
									border-radius: 999px;
									font-size: 1.1rem;
									cursor: pointer;
								}

								.nav-btn:hover { background: var(--accent-2); color: #fff; }

								.hint { color: var(--muted); }

								dialog {
									width: min(900px, 92vw);
									border-radius: 12px;
									border: none;
									background: #091222;
									color: #fff;
								}

								pre {
									background: #060d18;
									border: 1px solid rgba(255,255,255,0.12);
									border-radius: 10px;
									padding: 12px;
									max-height: 62vh;
									overflow: auto;
								}

								.upload-form {
									display: grid;
									gap: 10px;
									margin: 12px 0;
								}

								input, button {
									font: inherit;
								}

								button {
									border: none;
									border-radius: 10px;
									padding: 10px 14px;
									background: var(--accent);
									cursor: pointer;
								}

								.secondary {
									background: #4a5f84;
									color: white;
								}

								.message {
									min-height: 1.5em;
									color: #cde0ff;
								}

								@media (max-width: 700px) {
									.carousel-wrap {
										grid-template-columns: 1fr;
									}

									.nav-btn {
										width: 100%;
										border-radius: 10px;
									}

									.card img {
										height: min(70vw, 420px);
									}
								}
								""";
		}

		private static String jsContent() {
				return """
								(() => {
									const playerView = document.getElementById('player-view');
									const adminView = document.getElementById('admin-view');
									const imgEl = document.getElementById('game-image');
									const titleEl = document.getElementById('game-title');
									const cardEl = document.getElementById('card');
									const sourceDialog = document.getElementById('source-dialog');
									const sourceCodeEl = document.getElementById('source-code');
									const dialogTitleEl = document.getElementById('dialog-title');
									const adminMessage = document.getElementById('admin-message');
									const uploadForm = document.getElementById('upload-form');
									const javaFileInput = document.getElementById('java-file');
									const imageFileInput = document.getElementById('image-file');

									let games = [];
									let currentIndex = 0;
									let touchStartX = 0;
									const pressed = new Set();

									function escapeHtml(text) {
										return text
											.replaceAll('&', '&amp;')
											.replaceAll('<', '&lt;')
											.replaceAll('>', '&gt;');
									}

									async function fetchGamesFromApi() {
										const res = await fetch('/api/games');
										if (!res.ok) throw new Error('API error');
										return res.json();
									}

									function loadGamesFromLocalStorage() {
										const raw = localStorage.getItem('mini-games');
										if (!raw) return [];
										try {
											return JSON.parse(raw);
										} catch {
											return [];
										}
									}

									function saveGamesToLocalStorage(nextGames) {
										localStorage.setItem('mini-games', JSON.stringify(nextGames));
									}

									async function loadGames() {
										try {
											games = await fetchGamesFromApi();
										} catch {
											games = loadGamesFromLocalStorage();
										}

										if (!Array.isArray(games) || games.length === 0) {
											games = [{
												name: 'NoData',
												imageUrl: '',
												source: '登録されたミニゲームがありません。管理画面から追加してください。'
											}];
										}
										currentIndex = Math.min(currentIndex, games.length - 1);
										renderGame();
									}

									function renderGame() {
										const game = games[currentIndex];
										titleEl.textContent = game.name;
										imgEl.src = game.imageUrl || 'data:image/svg+xml;charset=UTF-8,' +
											encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360"><rect width="100%" height="100%" fill="#20324f"/><text x="50%" y="50%" dominant-baseline="middle" text-anchor="middle" fill="#fff" font-size="28">No Image</text></svg>');
									}

									function showSource() {
										const game = games[currentIndex];
										dialogTitleEl.textContent = game.name + ' 実行結果';
										sourceCodeEl.textContent = '実行中...';
										sourceDialog.showModal();

										fetch('/api/run', {
											method: 'POST',
											headers: { 'Content-Type': 'application/json' },
											body: JSON.stringify({ name: game.name })
										})
											.then(async res => {
												if (!res.ok) {
													throw new Error(await res.text());
												}
												return res.json();
											})
											.then(result => {
												const header = '[status] ' + (result.status || 'unknown') + '\n\n';
												sourceCodeEl.textContent = header + (result.output || '(outputなし)');
											})
											.catch(err => {
												sourceCodeEl.textContent = '実行に失敗しました:\n' + (err?.message || err);
											});
									}

									function nextGame() {
										if (games.length === 0) return;
										currentIndex = (currentIndex + 1) % games.length;
										renderGame();
									}

									function prevGame() {
										if (games.length === 0) return;
										currentIndex = (currentIndex - 1 + games.length) % games.length;
										renderGame();
									}

									function switchToAdmin() {
										playerView.classList.add('hidden');
										adminView.classList.remove('hidden');
									}

									function switchToPlayer() {
										adminView.classList.add('hidden');
										playerView.classList.remove('hidden');
									}

									function sanitizeName(fileName) {
										return fileName.replace(/\\.java$/i, '').replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 60) || 'Game';
									}

									async function uploadToApi(payload) {
										const res = await fetch('/api/games', {
											method: 'POST',
											headers: { 'Content-Type': 'application/json' },
											body: JSON.stringify(payload)
										});
										if (!res.ok) throw new Error(await res.text());
									}

									async function uploadToStorage(payload, imageDataUrl) {
										const localGames = loadGamesFromLocalStorage();
										const source = atob(payload.javaBase64);
										const existing = localGames.findIndex(g => g.name === payload.name);
										const next = {
											name: payload.name,
											imageUrl: imageDataUrl,
											source
										};
										if (existing >= 0) localGames[existing] = next;
										else localGames.push(next);
										saveGamesToLocalStorage(localGames);
									}

									function fileToText(file) {
										return new Promise((resolve, reject) => {
											const reader = new FileReader();
											reader.onload = () => resolve(String(reader.result));
											reader.onerror = reject;
											reader.readAsText(file, 'UTF-8');
										});
									}

									function fileToDataUrl(file) {
										return new Promise((resolve, reject) => {
											const reader = new FileReader();
											reader.onload = () => resolve(String(reader.result));
											reader.onerror = reject;
											reader.readAsDataURL(file);
										});
									}

									document.getElementById('next-btn').addEventListener('click', nextGame);
									document.getElementById('prev-btn').addEventListener('click', prevGame);
									document.getElementById('back-btn').addEventListener('click', switchToPlayer);
									cardEl.addEventListener('click', showSource);

									cardEl.addEventListener('touchstart', e => {
										touchStartX = e.changedTouches[0].clientX;
									}, { passive: true });

									cardEl.addEventListener('touchend', e => {
										const endX = e.changedTouches[0].clientX;
										const diff = endX - touchStartX;
										if (Math.abs(diff) > 40) {
											if (diff < 0) nextGame();
											else prevGame();
										}
									}, { passive: true });

									window.addEventListener('keydown', e => {
										pressed.add(e.key);
										if (pressed.has('g') && pressed.has('m') && pressed.has(';')) {
											switchToAdmin();
										}
									});

									window.addEventListener('keyup', e => {
										pressed.delete(e.key);
									});

									uploadForm.addEventListener('submit', async e => {
										e.preventDefault();
										adminMessage.textContent = '';

										const javaFile = javaFileInput.files?.[0];
										const imageFile = imageFileInput.files?.[0];
										if (!javaFile || !imageFile) {
											adminMessage.textContent = 'Javaファイルと画像を選択してください。';
											return;
										}

										try {
											const [javaText, imageDataUrl] = await Promise.all([
												fileToText(javaFile),
												fileToDataUrl(imageFile)
											]);

											const payload = {
												name: sanitizeName(javaFile.name),
												javaBase64: btoa(unescape(encodeURIComponent(javaText))),
												imageBase64: imageDataUrl.split(',')[1],
												imageExt: (imageFile.name.split('.').pop() || 'png').toLowerCase()
											};

											try {
												await uploadToApi(payload);
											} catch {
												await uploadToStorage(payload, imageDataUrl);
											}

											adminMessage.textContent = '登録しました。';
											uploadForm.reset();
											await loadGames();
										} catch (err) {
											adminMessage.textContent = '登録に失敗しました: ' + (err?.message || err);
										}
									});

									loadGames();
								})();
								""";
		}

		private static final class StaticFileHandler implements HttpHandler {
				private final Path webRoot;

				private StaticFileHandler(Path webRoot) {
						this.webRoot = webRoot;
				}

				@Override
				public void handle(HttpExchange exchange) throws IOException {
						String method = exchange.getRequestMethod();
						if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
								sendText(exchange, 405, "Method Not Allowed");
								return;
						}

						String rawPath = exchange.getRequestURI().getPath();
						if (rawPath == null || rawPath.equals("/")) {
								rawPath = "/" + INDEX_HTML;
						}

						Path resolved = safeResolve(webRoot, rawPath.substring(1));
						if (resolved == null || !Files.exists(resolved) || Files.isDirectory(resolved)) {
								sendText(exchange, 404, "Not Found");
								return;
						}

						String contentType = detectContentType(resolved);
						byte[] bytes = Files.readAllBytes(resolved);
						Headers headers = exchange.getResponseHeaders();
						headers.set("Content-Type", contentType);
						headers.set("Cache-Control", "no-cache");

						if ("HEAD".equalsIgnoreCase(method)) {
								exchange.sendResponseHeaders(200, -1);
								exchange.close();
								return;
						}

						exchange.sendResponseHeaders(200, bytes.length);
						try (OutputStream out = exchange.getResponseBody()) {
								out.write(bytes);
						}
				}
		}

		private static final class GameApiHandler implements HttpHandler {
				private final Path miniGameDir;
				private static final Pattern FIELD_PATTERN = Pattern.compile("\\\"(name|javaBase64|imageBase64|imageExt)\\\"\\s*:\\s*\\\"(.*?)\\\"");

				private GameApiHandler(Path miniGameDir) {
						this.miniGameDir = miniGameDir;
				}

				@Override
				public void handle(HttpExchange exchange) throws IOException {
						String method = exchange.getRequestMethod();
						if ("GET".equalsIgnoreCase(method)) {
								handleList(exchange);
								return;
						}
						if ("POST".equalsIgnoreCase(method)) {
								handleUpload(exchange);
								return;
						}
						sendText(exchange, 405, "Method Not Allowed");
				}

				private void handleList(HttpExchange exchange) throws IOException {
						List<GameRecord> records = listGames(miniGameDir);
						StringBuilder json = new StringBuilder("[");
						for (int i = 0; i < records.size(); i++) {
								GameRecord g = records.get(i);
								if (i > 0) json.append(',');
								json.append("{\"name\":\"").append(escapeJson(g.name)).append("\",")
												.append("\"imageUrl\":\"").append(escapeJson("/MiniGame/" + g.imageFileName)).append("\",")
												.append("\"source\":\"").append(escapeJson(g.source)).append("\"}");
						}
						json.append(']');
						sendJson(exchange, 200, json.toString());
				}

				private void handleUpload(HttpExchange exchange) throws IOException {
						byte[] bodyBytes;
						try (InputStream in = exchange.getRequestBody()) {
								bodyBytes = in.readAllBytes();
						}
						String body = new String(bodyBytes, StandardCharsets.UTF_8);
						Map<String, String> fields = parseFields(body);

						String nameRaw = fields.get("name");
						String javaBase64 = fields.get("javaBase64");
						String imageBase64 = fields.get("imageBase64");
						String imageExt = fields.get("imageExt");

						if (isBlank(nameRaw) || isBlank(javaBase64) || isBlank(imageBase64) || isBlank(imageExt)) {
								sendText(exchange, 400, "Invalid payload");
								return;
						}

						String safeName = sanitizeName(nameRaw);
						String safeExt = sanitizeExt(imageExt);
						if (safeExt == null) {
								sendText(exchange, 400, "Unsupported image extension");
								return;
						}

						byte[] javaBytes;
						byte[] imageBytes;
						try {
								javaBytes = Base64.getDecoder().decode(javaBase64);
								imageBytes = Base64.getDecoder().decode(imageBase64);
						} catch (IllegalArgumentException e) {
								sendText(exchange, 400, "Invalid base64");
								return;
						}

						Files.createDirectories(miniGameDir);

						Path javaPath = miniGameDir.resolve(safeName + ".java");
						removeOldImages(miniGameDir, safeName);
						Path imagePath = miniGameDir.resolve(safeName + "." + safeExt);

						Files.write(javaPath, javaBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
						Files.write(imagePath, imageBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

						sendJson(exchange, 200, "{\"status\":\"ok\"}");
				}
		}

		private static final class RunApiHandler implements HttpHandler {
				private final Path miniGameDir;
				private static final Pattern RUN_NAME_PATTERN = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"(.*?)\\\"");

				private RunApiHandler(Path miniGameDir) {
						this.miniGameDir = miniGameDir;
				}

				@Override
				public void handle(HttpExchange exchange) throws IOException {
						if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
								sendText(exchange, 405, "Method Not Allowed");
								return;
						}

						byte[] bodyBytes;
						try (InputStream in = exchange.getRequestBody()) {
								bodyBytes = in.readAllBytes();
						}
						String body = new String(bodyBytes, StandardCharsets.UTF_8);
						String nameRaw = parseRunName(body);
						if (isBlank(nameRaw)) {
								sendText(exchange, 400, "Invalid payload");
								return;
						}

						String safeName = sanitizeName(nameRaw);
						Path javaFile = miniGameDir.resolve(safeName + ".java");
						if (!Files.exists(javaFile)) {
								sendText(exchange, 404, "Java file not found");
								return;
						}

						String output;
						String status;
						cleanupGeneratedGameClasses(miniGameDir, safeName);
						try {
								ProcessBuilder compilePb = new ProcessBuilder("javac", javaFile.getFileName().toString());
								compilePb.directory(miniGameDir.toFile());
								compilePb.redirectErrorStream(true);
								Process compileProcess = compilePb.start();
								boolean compileFinished = compileProcess.waitFor(20, TimeUnit.SECONDS);
								String compileOut = readProcessOutput(compileProcess);

								if (!compileFinished) {
										compileProcess.destroyForcibly();
										status = "compile_timeout";
										output = compileOut + "\n[error] コンパイルがタイムアウトしました。";
								} else if (compileProcess.exitValue() != 0) {
										status = "compile_error";
										output = compileOut;
								} else {
										ProcessBuilder runPb = new ProcessBuilder("java", "-cp", ".", safeName);
										runPb.directory(miniGameDir.toFile());
										runPb.redirectErrorStream(true);
										Process runProcess = runPb.start();
										boolean runFinished = runProcess.waitFor(20, TimeUnit.SECONDS);
										String runOut = readProcessOutput(runProcess);

										if (!runFinished) {
												runProcess.destroyForcibly();
												status = "run_timeout";
												output = runOut + "\n[error] 実行がタイムアウトしました。";
										} else {
												status = runProcess.exitValue() == 0 ? "ok" : "run_error";
												output = runOut;
										}
								}
						} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								status = "interrupted";
								output = "実行が中断されました。";
						} finally {
								cleanupGeneratedGameClasses(miniGameDir, safeName);
						}

						String json = "{\"status\":\"" + escapeJson(status) + "\",\"output\":\"" + escapeJson(output) + "\"}";
						sendJson(exchange, 200, json);
				}

				private static String parseRunName(String body) {
						Matcher matcher = RUN_NAME_PATTERN.matcher(body);
						if (!matcher.find()) {
								return null;
						}
						return unescapeJson(matcher.group(1));
				}

				private static String readProcessOutput(Process process) throws IOException {
						try (InputStream in = process.getInputStream()) {
								byte[] bytes = in.readAllBytes();
								return new String(bytes, StandardCharsets.UTF_8);
						}
				}
		}

		private static void cleanupGeneratedGameClasses(Path miniGameDir, String safeName) {
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(miniGameDir, safeName + "*.class")) {
						for (Path classFile : stream) {
								Files.deleteIfExists(classFile);
						}
				} catch (IOException ignored) {
						// Cleanup best-effort.
				}
		}

		private static void removeOldImages(Path miniGameDir, String safeName) throws IOException {
				String[] exts = {"png", "jpg", "jpeg", "gif", "webp"};
				for (String ext : exts) {
						Files.deleteIfExists(miniGameDir.resolve(safeName + "." + ext));
				}
		}

		private static List<GameRecord> listGames(Path miniGameDir) throws IOException {
				List<GameRecord> out = new ArrayList<>();
				if (!Files.exists(miniGameDir)) {
						return out;
				}

				try (DirectoryStream<Path> stream = Files.newDirectoryStream(miniGameDir, "*.java")) {
						for (Path javaFile : stream) {
								String fileName = javaFile.getFileName().toString();
								String base = fileName.substring(0, fileName.length() - ".java".length());
								Path image = findImage(miniGameDir, base);
								if (image == null) {
										continue;
								}
								String source = Files.readString(javaFile, StandardCharsets.UTF_8);
								out.add(new GameRecord(base, image.getFileName().toString(), source));
						}
				}

				out.sort(Comparator.comparing(g -> g.name.toLowerCase()));
				return out;
		}

		private static Path findImage(Path dir, String baseName) {
				String[] exts = {"png", "jpg", "jpeg", "gif", "webp"};
				for (String ext : exts) {
						Path p = dir.resolve(baseName + "." + ext);
						if (Files.exists(p)) {
								return p;
						}
				}
				return null;
		}

		private static Map<String, String> parseFields(String json) {
				Map<String, String> fields = new HashMap<>();
				Matcher m = GameApiHandler.FIELD_PATTERN.matcher(json);
				while (m.find()) {
						String key = m.group(1);
						String value = unescapeJson(m.group(2));
						fields.put(key, value);
				}
				return fields;
		}

		private static Path safeResolve(Path root, String requestPath) {
				String decoded = URLDecoder.decode(requestPath, StandardCharsets.UTF_8);
				Path resolved = root.resolve(decoded).normalize();
				if (!resolved.startsWith(root.normalize())) {
						return null;
				}
				return resolved;
		}

		private static String detectContentType(Path path) {
				String name = path.getFileName().toString().toLowerCase();
				if (name.endsWith(".html")) return "text/html; charset=utf-8";
				if (name.endsWith(".css")) return "text/css; charset=utf-8";
				if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
				if (name.endsWith(".png")) return "image/png";
				if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
				if (name.endsWith(".gif")) return "image/gif";
				if (name.endsWith(".webp")) return "image/webp";
				if (name.endsWith(".java")) return "text/plain; charset=utf-8";
				return "application/octet-stream";
		}

		private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
				byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
				exchange.sendResponseHeaders(status, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
						out.write(bytes);
				}
		}

		private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
				byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
				exchange.sendResponseHeaders(status, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
						out.write(bytes);
				}
		}

		private static String sanitizeName(String raw) {
				String s = Objects.requireNonNullElse(raw, "").trim();
				s = s.replaceAll("[^A-Za-z0-9_-]", "_");
				if (s.isBlank()) {
						s = "Game";
				}
				if (s.length() > 60) {
						s = s.substring(0, 60);
				}
				return s;
		}

		private static String sanitizeExt(String ext) {
				String e = Objects.requireNonNullElse(ext, "").toLowerCase();
				return switch (e) {
						case "png", "jpg", "jpeg", "gif", "webp" -> e;
						default -> null;
				};
		}

		private static boolean isBlank(String s) {
				return s == null || s.isBlank();
		}

		private static String escapeJson(String s) {
				StringBuilder sb = new StringBuilder(s.length() + 16);
				for (int i = 0; i < s.length(); i++) {
						char c = s.charAt(i);
						switch (c) {
								case '"' -> sb.append("\\\"");
								case '\\' -> sb.append("\\\\");
								case '\b' -> sb.append("\\b");
								case '\f' -> sb.append("\\f");
								case '\n' -> sb.append("\\n");
								case '\r' -> sb.append("\\r");
								case '\t' -> sb.append("\\t");
								default -> {
										if (c < 0x20) {
												sb.append(String.format("\\u%04x", (int) c));
										} else {
												sb.append(c);
										}
								}
						}
				}
				return sb.toString();
		}

		private static String unescapeJson(String s) {
				StringBuilder sb = new StringBuilder(s.length());
				for (int i = 0; i < s.length(); i++) {
						char c = s.charAt(i);
						if (c != '\\') {
								sb.append(c);
								continue;
						}
						if (i + 1 >= s.length()) {
								sb.append(c);
								break;
						}
						char n = s.charAt(++i);
						switch (n) {
								case '"' -> sb.append('"');
								case '\\' -> sb.append('\\');
								case '/' -> sb.append('/');
								case 'b' -> sb.append('\b');
								case 'f' -> sb.append('\f');
								case 'n' -> sb.append('\n');
								case 'r' -> sb.append('\r');
								case 't' -> sb.append('\t');
								case 'u' -> {
										if (i + 4 < s.length()) {
												String hex = s.substring(i + 1, i + 5);
												try {
														int code = Integer.parseInt(hex, 16);
														sb.append((char) code);
														i += 4;
												} catch (NumberFormatException e) {
														sb.append("\\u").append(hex);
														i += 4;
												}
										} else {
												sb.append("\\u");
										}
								}
								default -> sb.append(n);
						}
				}
				return sb.toString();
		}

		private record GameRecord(String name, String imageFileName, String source) {
		}
}
