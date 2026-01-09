package example;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.avaje.jex.Jex;
import io.avaje.jex.http.Context;
import io.avaje.jex.staticcontent.StaticContent;
import io.avaje.webview.Webview;

public class Main {
  // Simple in-memory state
  static boolean timerActive = false;
  static LocalDateTime startTime;
  static List<String> tasks = new ArrayList<>();
  static int completedTasks = 0;

  static void main() {
    // needs JVM argument -XstartOnFirstThread on Macos
    Jex.Server server =
        Jex.create()
            .plugin(StaticContent.ofClassPath("/static/favicon.ico").route("/favicon.ico").build())
            .plugin(StaticContent.ofClassPath("/static/index.html").route("/").build())
            .get("/timer/status", Main::countDown)
            // Add Task
            .post(
                "/tasks/add",
                ctx -> {
                  String taskName = ctx.formParam("task_name");
                  if (taskName != null && !taskName.isBlank()) {
                    String taskHtml =
                        String.format(
                            """
                            <li class='bg-white p-5 rounded-3xl border border-slate-100 shadow-sm flex items-center justify-between group'>
                              <div class='flex flex-col'>
                                <span class='font-semibold text-slate-700'>%s</span>
                              </div>
                              <button hx-post='/tasks/complete' hx-target='closest li' hx-swap='outerHTML' class='w-8 h-8 rounded-full border-2 border-slate-200 hover:border-indigo-500 active:bg-indigo-500 transition-all shadow-inner'></button>
                            </li>""",
                            taskName);
                    ctx.html(taskHtml);
                  }
                })
            .port(0) // random port
            .start();

    int port = server.port();

    try {
      Webview webview =
          Webview.builder().title("Pulse Focus").url("http://localhost:" + port).enableDeveloperTools(true).build();
      // Bind function to start the timer
      webview.bind(
          "__timerStart__",
          _ -> {
            timerActive = true;
            startTime = LocalDateTime.now();
            return "\"ok\"";
          });

      // Bind function to notify backend when timer completes
      webview.bind(
          "__timerComplete__",
          _ -> {
            timerActive = false;
            completedTasks++;
            return "\"ok\"";
          });

      // Bind function to cancel/stop the timer
      webview.bind(
          "__timerCancel__",
          _ -> {
            timerActive = false;
            startTime = null;
            return "\"ok\"";
          });

      // Bind function to get completed sessions count
      webview.bind("__getCompletedSessions__", _ -> String.valueOf(completedTasks));
      webview.maximizeWindow();
      webview.run();
    } finally {
     server.shutdown();
    }
  }

  private static void countDown(Context ctx) {
    ctx.html(
        """
        <div class="timer-font text-7xl font-black text-slate-800 mb-2" id="timer-value">
            25:00
        </div>
        <p class="text-slate-400 font-medium">Ready to begin?</p>

        <div class="mt-10 flex gap-4">
            <button onclick="startTimer()"
                    class="flex-[2] bg-indigo-600 text-white py-4 rounded-2xl font-bold active:scale-95 transition-transform shadow-lg shadow-indigo-100">
                Start Session
            </button>
            <button onclick="resetTimer()"
                    class="flex-1 bg-slate-100 text-slate-500 py-4 rounded-2xl font-bold active:scale-95 transition-transform">
                Reset
            </button>
        </div>

        <script>
        let timerInterval = null;

        async function startTimer() {
            // Notify backend to start
            await window.__timerStart__();

            // Start countdown
            let secondsLeft = 25 * 60;
            const timerEl = document.getElementById('timer-value');
            const container = document.getElementById('timer-display');

            // Update UI to show active state
            timerEl.className = 'timer-font text-7xl font-black text-indigo-600 mb-2';
            container.querySelector('p').textContent = 'Focus Mode Active';
            container.querySelector('p').className = 'text-indigo-400 font-medium animate-pulse';

            // Replace buttons
            container.querySelector('.flex.gap-4').innerHTML = `
                <button onclick="stopTimer()"
                        class="flex-1 bg-red-500 text-white py-4 rounded-2xl font-bold active:scale-95 transition-transform shadow-lg shadow-red-100">
                    Stop
                </button>
            `;

            function updateDisplay() {
                const mins = Math.floor(secondsLeft / 60);
                const secs = secondsLeft % 60;
                timerEl.textContent = String(mins).padStart(2, '0') + ':' + String(secs).padStart(2, '0');
            }

            updateDisplay();

            timerInterval = setInterval(async () => {
                secondsLeft--;

                if (secondsLeft <= 0) {
                    clearInterval(timerInterval);
                    timerInterval = null;
                    timerEl.textContent = '00:00';
                    timerEl.className = 'timer-font text-7xl font-black text-green-600 mb-2';

                    // Notify backend of completion
                    await window.__timerComplete__();

                    // Update badge
                    const badge = document.getElementById('daily-badge');
                    const sessions = await window.__getCompletedSessions__();
                    badge.textContent = sessions + ' SESSIONS';
                    badge.className = 'bg-green-600 text-white text-xs font-bold px-4 py-2 rounded-full shadow-lg shadow-green-200 transition-all';

                    // Show completion message
                    container.querySelector('p').textContent = 'Session Complete! 🎉';
                    container.querySelector('p').className = 'text-green-400 font-medium';

                    setTimeout(() => {
                        htmx.ajax('GET', '/timer/status', {target: '#timer-display'});
                    }, 2000);
                } else {
                    updateDisplay();
                }
            }, 1000);
        }

        async function stopTimer() {
            if (timerInterval) {
                clearInterval(timerInterval);
                timerInterval = null;
            }

            // Notify backend of cancellation
            await window.__timerCancel__();

            // Reload timer display
            htmx.ajax('GET', '/timer/status', {target: '#timer-display'});
        }

        async function resetTimer() {
            await window.__timerCancel__();
            htmx.ajax('GET', '/timer/status', {target: '#timer-display'});
        }
        </script>
        """);
  }
}
