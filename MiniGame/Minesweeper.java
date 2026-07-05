package MiniGame;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
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
import java.util.Comparator;
import java.util.concurrent.Executors;

public class Minesweeper {
		private static final String HOST = "127.0.0.1";

		public static void main(String[] args) throws Exception {
				Path runtimeRoot = Files.createTempDirectory("minesweeper-runtime-");
				Path webRoot = runtimeRoot.resolve("web");
				Files.createDirectories(webRoot);

				Files.writeString(webRoot.resolve("index.html"), html(), StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				Files.writeString(webRoot.resolve("styles.css"), css(), StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				Files.writeString(webRoot.resolve("app.js"), js(), StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
						try {
								deleteRecursively(runtimeRoot);
						} catch (IOException ignored) {
								// best effort
						}
						cleanupMinesweeperClassFiles();
				}));

				HttpServer server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
				server.createContext("/", new StaticHandler(webRoot));
				server.setExecutor(Executors.newCachedThreadPool());
				server.start();

				int port = server.getAddress().getPort();
				String url = "http://" + HOST + ":" + port + "/";

				System.out.println("Minesweeper started: " + url);
				System.out.println("Ctrl+C で終了すると一時ファイルは削除されます。");

				if (Desktop.isDesktopSupported()) {
						Desktop.getDesktop().browse(URI.create(url));
				} else {
						System.out.println("ブラウザを自動起動できないため、URLを手動で開いてください。");
				}
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
										// best effort
								}
						});
				}
		}

		private static void cleanupMinesweeperClassFiles() {
				try {
						Path classDir = getMinesweeperClassDirectory();
						if (classDir == null || !Files.isDirectory(classDir)) {
								return;
						}

						try (DirectoryStream<Path> stream = Files.newDirectoryStream(classDir, "Minesweeper*.class")) {
								for (Path classFile : stream) {
										try {
												Files.deleteIfExists(classFile);
										} catch (IOException e) {
												classFile.toFile().deleteOnExit();
										}
								}
						}
				} catch (IOException ignored) {
						// best effort
				}
		}

		private static Path getMinesweeperClassDirectory() {
				try {
						var source = Minesweeper.class.getProtectionDomain().getCodeSource();
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

		private static String html() {
				return """
								<!doctype html>
								<html lang="ja">
								<head>
									<meta charset="utf-8" />
									<meta name="viewport" content="width=device-width, initial-scale=1" />
									<title>Minesweeper</title>
									<link rel="stylesheet" href="/styles.css" />
								</head>
								<body>
									<main class="shell">
										<header class="hero">
											<h1>Neon Minesweeper</h1>
											<p>最初の1クリックは必ず安全。右クリックで旗を立てられます。</p>
										</header>

										<section id="setup" class="panel">
											<h2>設定</h2>
											<div class="setup-grid">
												<label>
													横幅
													<input id="cols" type="number" min="5" max="40" value="12" />
												</label>

												<label id="rows-wrap">
													縦幅
													<input id="rows" type="number" min="5" max="40" value="12" />
												</label>

												<label id="bombs-wrap">
													爆弾の数
													<input id="bombs" type="number" min="1" value="20" />
												</label>

												<div class="checks-fixed">
													<label class="toggle-row">
														<input id="square" type="checkbox" checked />
														正方形にする
													</label>

													<label class="toggle-row">
														<input id="random-bombs" type="checkbox" />
														爆弾数をランダムにする
													</label>
												</div>
											</div>

											<div class="actions">
												<button id="start" class="primary">ゲーム開始</button>
											</div>
											<p id="setup-note" class="note"></p>
										</section>

										<section id="game" class="panel hidden">
											<div class="hud">
												<div>経過: <strong id="time">0</strong>s</div>
												<div id="bombs-info">爆弾: <strong id="bomb-count">0</strong></div>
												<div>旗: <strong id="flags">0</strong></div>
												<div>連勝: <strong id="streak">0</strong></div>
											</div>

											<div class="actions">
												<button id="restart" class="primary">同じ設定で再開</button>
												<button id="to-setup" class="ghost">設定へ戻る</button>
											</div>

											<p id="status" class="status">盤面をクリックして開始</p>
											<div id="board" class="board" aria-label="Minesweeper Board"></div>
										</section>
									</main>
									<script src="/app.js"></script>
								</body>
								</html>
								""";
		}

		private static String css() {
				return """
								:root {
									--bg: #09131f;
									--panel: rgba(8, 21, 35, 0.84);
									--line: #2ec4b6;
									--text: #eaf7ff;
									--muted: #a8c6db;
									--good: #5be37d;
									--warn: #ffb703;
									--bad: #ff4d6d;
								}

								* { box-sizing: border-box; }

								body {
									margin: 0;
									min-height: 100vh;
									font-family: "Trebuchet MS", "Yu Gothic", sans-serif;
									color: var(--text);
									background:
										radial-gradient(900px 500px at 0% 10%, rgba(46,196,182,0.22), transparent 60%),
										radial-gradient(600px 420px at 100% 100%, rgba(255,183,3,0.18), transparent 62%),
										var(--bg);
								}

								.shell {
									width: min(1100px, 96vw);
									margin: 18px auto 28px;
								}

								.hero h1 {
									margin: 0;
									font-size: clamp(1.8rem, 4vw, 3rem);
									letter-spacing: 1px;
								}

								.hero p { margin-top: 8px; color: var(--muted); }

								.panel {
									margin-top: 16px;
									border: 1px solid rgba(46,196,182,0.35);
									background: var(--panel);
									border-radius: 14px;
									padding: 16px;
									backdrop-filter: blur(5px);
								}

								.hidden { display: none; }
								.invisible {
									visibility: hidden;
									pointer-events: none;
								}

								.setup-grid {
									display: grid;
									grid-template-columns: repeat(2, minmax(180px, 1fr));
									gap: 10px;
								}

								.checks-fixed {
									display: grid;
									gap: 8px;
									align-content: start;
								}

								label {
									display: grid;
									gap: 6px;
									color: var(--muted);
								}

								.toggle-row {
									display: flex;
									align-items: center;
									gap: 8px;
								}

								input[type="number"] {
									border: 1px solid rgba(255,255,255,0.2);
									border-radius: 9px;
									padding: 9px 10px;
									font: inherit;
									background: rgba(255,255,255,0.05);
									color: var(--text);
								}

								.actions {
									margin-top: 12px;
									display: flex;
									flex-wrap: wrap;
									gap: 8px;
								}

								button {
									border: none;
									border-radius: 10px;
									padding: 10px 14px;
									font: inherit;
									cursor: pointer;
								}

								.primary {
									background: linear-gradient(135deg, var(--line), #1ea896);
									color: #052126;
									font-weight: 700;
								}

								.ghost {
									background: rgba(255,255,255,0.08);
									color: var(--text);
								}

								.note { min-height: 1.5em; color: var(--warn); }

								.hud {
									display: grid;
									grid-template-columns: repeat(4, minmax(120px, 1fr));
									gap: 8px;
								}

								.hud > div {
									background: rgba(255,255,255,0.05);
									border-radius: 10px;
									padding: 8px 10px;
								}

								.status {
									min-height: 1.5em;
									font-weight: 600;
								}

								.board {
									margin-top: 10px;
									display: grid;
									gap: 0;
									width: min(100%, 900px);
									aspect-ratio: 1 / 1;
									border: 1px solid rgba(255,255,255,0.18);
									user-select: none;
									touch-action: manipulation;
								}

								.cell {
									width: 100%;
									height: 100%;
									aspect-ratio: 1 / 1;
									border: 1px solid rgba(255,255,255,0.12);
									border-radius: 0;
									display: grid;
									place-items: center;
									font-weight: 700;
									background: #13314a;
									color: #f3fbff;
									cursor: pointer;
								}

								.cell:hover { filter: brightness(1.08); }

								.open { background: #072033; }
								.bomb { background: #4a1020; color: #ffd6df; }
								.flag { background: #4b3b09; color: #ffe29d; }

								.n1 { color: #89f0ff; }
								.n2 { color: #8afc94; }
								.n3 { color: #ffd479; }
								.n4 { color: #ff9cb0; }
								.n5, .n6, .n7, .n8 { color: #fefefe; }

								@media (max-width: 900px) {
									.setup-grid { grid-template-columns: 1fr; }
									.hud { grid-template-columns: repeat(2, minmax(120px, 1fr)); }
									.cell { font-size: 0.9rem; }
								}
								""";
		}

		private static String js() {
				return """
								(() => {
									const setupView = document.getElementById('setup');
									const gameView = document.getElementById('game');
									const colsInput = document.getElementById('cols');
									const rowsInput = document.getElementById('rows');
									const bombsInput = document.getElementById('bombs');
									const squareInput = document.getElementById('square');
									const randomBombsInput = document.getElementById('random-bombs');
									const rowsWrap = document.getElementById('rows-wrap');
									const bombsWrap = document.getElementById('bombs-wrap');
									const setupNote = document.getElementById('setup-note');
									const boardEl = document.getElementById('board');
									const statusEl = document.getElementById('status');
									const timeEl = document.getElementById('time');
									const bombCountEl = document.getElementById('bomb-count');
									const bombsInfoEl = document.getElementById('bombs-info');
									const flagsEl = document.getElementById('flags');
									const streakEl = document.getElementById('streak');

									const startBtn = document.getElementById('start');
									const restartBtn = document.getElementById('restart');
									const toSetupBtn = document.getElementById('to-setup');

									let state = null;
									let timerId = null;
									let seconds = 0;
									let streak = 0;

									function clamp(v, min, max) {
										return Math.min(max, Math.max(min, v));
									}

									function updateSetupUi() {
										if (squareInput.checked) {
											rowsWrap.classList.add('invisible');
										} else {
											rowsWrap.classList.remove('invisible');
										}

										if (randomBombsInput.checked) {
											bombsWrap.classList.add('invisible');
											const c = clamp(Number(colsInput.value) || 12, 5, 40);
											const r = squareInput.checked ? c : clamp(Number(rowsInput.value) || 12, 5, 40);
											const total = c * r;
											const minBombs = Math.max(1, Math.floor(total * 0.2));
											const maxBombs = Math.max(minBombs, Math.floor(total * 0.5));
											setupNote.textContent = '爆弾数はランダム: ' + minBombs + ' 〜 ' + maxBombs;
										} else {
											bombsWrap.classList.remove('invisible');
											setupNote.textContent = '';
										}
									}

									function pickRandomBombCount(totalCells) {
										const minBombs = Math.max(1, Math.floor(totalCells * 0.2));
										const maxBombs = Math.max(minBombs, Math.floor(totalCells * 0.5));
										return minBombs + Math.floor(Math.random() * (maxBombs - minBombs + 1));
									}

									function makeCell() {
										return { bomb: false, open: false, flag: false, around: 0 };
									}

									function createState(config) {
										const board = Array.from({ length: config.rows }, () =>
											Array.from({ length: config.cols }, makeCell)
										);

										return {
											rows: config.rows,
											cols: config.cols,
											bombCount: config.bombCount,
											randomBombs: config.randomBombs,
											board,
											started: false,
											finished: false,
											openCount: 0,
											flags: 0
										};
									}

									function forEachNeighbor(r, c, fn) {
										for (let dr = -1; dr <= 1; dr++) {
											for (let dc = -1; dc <= 1; dc++) {
												if (dr === 0 && dc === 0) continue;
												const nr = r + dr;
												const nc = c + dc;
												if (nr < 0 || nc < 0 || nr >= state.rows || nc >= state.cols) continue;
												fn(nr, nc);
											}
										}
									}

									function placeBombsAvoiding(firstR, firstC) {
										const total = state.rows * state.cols;
										const firstIndex = firstR * state.cols + firstC;
										const bag = [];
										for (let i = 0; i < total; i++) {
											if (i !== firstIndex) bag.push(i);
										}

										for (let i = bag.length - 1; i > 0; i--) {
											const j = Math.floor(Math.random() * (i + 1));
											const tmp = bag[i];
											bag[i] = bag[j];
											bag[j] = tmp;
										}

										for (let i = 0; i < state.bombCount; i++) {
											const idx = bag[i];
											const r = Math.floor(idx / state.cols);
											const c = idx % state.cols;
											state.board[r][c].bomb = true;
										}

										for (let r = 0; r < state.rows; r++) {
											for (let c = 0; c < state.cols; c++) {
												if (state.board[r][c].bomb) continue;
												let around = 0;
												forEachNeighbor(r, c, (nr, nc) => {
													if (state.board[nr][nc].bomb) around++;
												});
												state.board[r][c].around = around;
											}
										}
									}

									function renderBoard() {
										boardEl.innerHTML = '';
										boardEl.style.gridTemplateColumns = 'repeat(' + state.cols + ', 1fr)';
										boardEl.style.gridTemplateRows = 'repeat(' + state.rows + ', 1fr)';
										boardEl.style.aspectRatio = state.cols + ' / ' + state.rows;

										for (let r = 0; r < state.rows; r++) {
											for (let c = 0; c < state.cols; c++) {
												const cell = state.board[r][c];
												const el = document.createElement('button');
												el.type = 'button';
												el.className = 'cell';
												el.dataset.r = String(r);
												el.dataset.c = String(c);

												if (cell.open) {
													el.classList.add('open');
													if (cell.bomb) {
														el.classList.add('bomb');
														el.textContent = '💣';
													} else if (cell.around > 0) {
														el.classList.add('n' + cell.around);
														el.textContent = String(cell.around);
													}
												} else if (cell.flag) {
													el.classList.add('flag');
													el.textContent = '🚩';
												}

												boardEl.appendChild(el);
											}
										}

										flagsEl.textContent = String(state.flags);
										if (state.randomBombs) {
											bombsInfoEl.classList.add('hidden');
										} else {
											bombsInfoEl.classList.remove('hidden');
											bombCountEl.textContent = String(state.bombCount);
										}
										streakEl.textContent = String(streak);
									}

									function openCell(r, c) {
										const cell = state.board[r][c];
										if (cell.open || cell.flag) return;

										cell.open = true;
										state.openCount++;

										if (cell.bomb) {
											endGame(false, '爆弾に触れました。');
											return;
										}

										if (cell.around === 0) {
											forEachNeighbor(r, c, (nr, nc) => {
												if (!state.board[nr][nc].open) {
													openCell(nr, nc);
												}
											});
										}
									}

									function revealAllBombs() {
										for (let r = 0; r < state.rows; r++) {
											for (let c = 0; c < state.cols; c++) {
												if (state.board[r][c].bomb) state.board[r][c].open = true;
											}
										}
									}

									function checkWin() {
										const safeCells = state.rows * state.cols - state.bombCount;
										if (state.openCount >= safeCells && !state.finished) {
											endGame(true, 'クリア! 盤面を制覇しました。');
										}
									}

									function tickTimerStartOnce() {
										if (timerId !== null) return;
										timerId = setInterval(() => {
											seconds++;
											timeEl.textContent = String(seconds);
										}, 1000);
									}

									function stopTimer() {
										if (timerId !== null) {
											clearInterval(timerId);
											timerId = null;
										}
									}

									function endGame(win, message) {
										state.finished = true;
										if (!win) revealAllBombs();
										stopTimer();
										statusEl.textContent = message;
										if (win) {
											statusEl.style.color = 'var(--good)';
											streak++;
										} else {
											statusEl.style.color = 'var(--bad)';
											streak = 0;
										}
										renderBoard();
									}

									function handlePrimaryOpen(r, c) {
										if (state.finished) return;
										const cell = state.board[r][c];
										if (cell.flag || cell.open) return;

										if (!state.started) {
											state.started = true;
											placeBombsAvoiding(r, c);
											tickTimerStartOnce();
											statusEl.textContent = '進行中';
											statusEl.style.color = 'var(--text)';
										}

										openCell(r, c);
										checkWin();
										renderBoard();
									}

									function toggleFlag(r, c) {
										if (state.finished) return;
										const cell = state.board[r][c];
										if (cell.open) return;
										cell.flag = !cell.flag;
										state.flags += cell.flag ? 1 : -1;
										renderBoard();
									}

									function restartWithConfig(config) {
										stopTimer();
										seconds = 0;
										timeEl.textContent = '0';
										state = createState(config);
										statusEl.textContent = '盤面をクリックして開始';
										statusEl.style.color = 'var(--text)';
										renderBoard();
										setupView.classList.add('hidden');
										gameView.classList.remove('hidden');
									}

									function parseConfigFromSetup() {
										let cols = clamp(Number(colsInput.value) || 12, 5, 40);
										let rows = squareInput.checked ? cols : clamp(Number(rowsInput.value) || 12, 5, 40);

										const total = rows * cols;
										let bombCount;
										if (randomBombsInput.checked) {
											bombCount = pickRandomBombCount(total);
										} else {
											const maxByRule = Math.max(1, total - 1);
											bombCount = clamp(Number(bombsInput.value) || 1, 1, maxByRule);
										}

										// If total is very small, keep at least one safe cell.
										bombCount = Math.min(bombCount, total - 1);

										return {
											cols,
											rows,
											bombCount,
											randomBombs: randomBombsInput.checked
										};
									}

									boardEl.addEventListener('click', e => {
										const t = e.target;
										if (!(t instanceof HTMLElement)) return;
										if (!t.classList.contains('cell')) return;
										const r = Number(t.dataset.r);
										const c = Number(t.dataset.c);
										handlePrimaryOpen(r, c);
									});

									boardEl.addEventListener('contextmenu', e => {
										e.preventDefault();
										const t = e.target;
										if (!(t instanceof HTMLElement)) return;
										if (!t.classList.contains('cell')) return;
										const r = Number(t.dataset.r);
										const c = Number(t.dataset.c);
										toggleFlag(r, c);
									});

									squareInput.addEventListener('change', () => {
										if (squareInput.checked) {
											rowsInput.value = colsInput.value;
										}
										updateSetupUi();
									});

									colsInput.addEventListener('input', () => {
										if (squareInput.checked) {
											rowsInput.value = colsInput.value;
										}
										updateSetupUi();
									});

									rowsInput.addEventListener('input', updateSetupUi);
									randomBombsInput.addEventListener('change', updateSetupUi);

									startBtn.addEventListener('click', () => {
										const config = parseConfigFromSetup();
										restartWithConfig(config);
									});

									restartBtn.addEventListener('click', () => {
										if (!state) return;
										const config = {
											rows: state.rows,
											cols: state.cols,
											bombCount: state.randomBombs ? pickRandomBombCount(state.rows * state.cols) : state.bombCount,
											randomBombs: state.randomBombs
										};
										restartWithConfig(config);
									});

									toSetupBtn.addEventListener('click', () => {
										stopTimer();
										gameView.classList.add('hidden');
										setupView.classList.remove('hidden');
										updateSetupUi();
									});

									updateSetupUi();
								})();
								""";
		}

		private static final class StaticHandler implements HttpHandler {
				private final Path webRoot;

				private StaticHandler(Path webRoot) {
						this.webRoot = webRoot;
				}

				@Override
				public void handle(HttpExchange exchange) throws IOException {
						String method = exchange.getRequestMethod();
						if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
								sendText(exchange, 405, "Method Not Allowed");
								return;
						}

						String reqPath = exchange.getRequestURI().getPath();
						if (reqPath == null || reqPath.equals("/")) {
								reqPath = "/index.html";
						}

						Path target = safeResolve(webRoot, reqPath.substring(1));
						if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
								sendText(exchange, 404, "Not Found");
								return;
						}

						byte[] bytes = Files.readAllBytes(target);
						Headers headers = exchange.getResponseHeaders();
						headers.set("Content-Type", contentType(target));
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

		private static Path safeResolve(Path root, String reqPath) {
				String decoded = URLDecoder.decode(reqPath, StandardCharsets.UTF_8);
				Path resolved = root.resolve(decoded).normalize();
				if (!resolved.startsWith(root.normalize())) {
						return null;
				}
				return resolved;
		}

		private static String contentType(Path path) {
				String name = path.getFileName().toString().toLowerCase();
				if (name.endsWith(".html")) return "text/html; charset=utf-8";
				if (name.endsWith(".css")) return "text/css; charset=utf-8";
				if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
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
}
