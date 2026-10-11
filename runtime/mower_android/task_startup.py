"""Admit automatic tasks once per Android service process."""

from threading import Event, Lock, Thread


def start_tasks(server, config, token):
    app = server.app
    if app.extensions.get("android_task_startup"):
        return
    app.extensions["android_task_startup"] = True
    # This legacy shared-WebUI flag also suppresses desktop-era resource startup.
    app.config["DESKTOP_STARTUP_HANDLED"] = True
    cancelled = Event()
    admission_lock = Lock()
    automatic = config.conf.start_automatically
    resource = getattr(config.conf, "resource_update", None)
    if resource is None:
        resource = config.conf.hot_update
    auto_resource = resource.enable and resource.auto_update

    from flask import request

    @app.before_request
    def cancel_pending_start():
        if request.path == "/stop":
            # Older Mower versions return immediately when no task exists yet.
            with admission_lock:
                cancelled.set()
                config.stop_mower.set()

    @app.after_request
    def report_startup_owner(response):
        if (
            request.path == "/status"
            and response.status_code == 200
            and response.is_json
        ):
            data = response.get_json()
            data.update(auto_start_handled=True, desktop_startup_handled=True)
            response.set_data(app.json.dumps(data))
        return response

    def stopping():
        return cancelled.is_set() or config.stop_mower.is_set()

    def run():
        while not stopping() and server.active_job():
            cancelled.wait(0.5)
        if stopping():
            return
        with app.test_request_context(method="POST", headers={"token": token}):
            if auto_resource:
                try:
                    info = server.get_resource_version()
                    if info.get("update_available") is True and not stopping():
                        server.install_resource()
                except Exception:
                    from arknights_mower.utils.log import logger

                    logger.exception("启动时自动更新资源失败")
                # A cached WebUI or manual request may already own installation.
                if server.resource_update.running():
                    server.resource_update.thread.join()
            with admission_lock:
                if automatic and not stopping():
                    server.start("0")

    if automatic or auto_resource:
        Thread(target=run, name="android-task-startup", daemon=True).start()
