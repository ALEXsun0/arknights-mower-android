import io
import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'runtime'))
from mower_android import mower_package, app_update


class MowerPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        context=patch.dict(os.environ, {'MOWER_DATA_DIR':str(self.root), 'MOWER_SERVICE_RESTART':''})
        context.start(); self.addCleanup(context.stop)
        for name, value in (('_job', {'ok':True, 'status':'idle'}), ('_plans', {}), ('_last_check', {})):
            isolated = patch.object(app_update, name, value)
            isolated.start(); self.addCleanup(isolated.stop)
        preparation = patch.object(app_update, 'prepare_restart')
        self.prepare_restart = preparation.start(); self.addCleanup(preparation.stop)
        self.prepare_restart.return_value = False
        jobs = patch('mower_android.background_cleanup.submit', side_effect=lambda key, action: action())
        jobs.start(); self.addCleanup(jobs.stop)
        os.environ.pop('MOWER_ACTIVE_ID', None)
        self.addCleanup(os.environ.pop, 'MOWER_ACTIVE_ID', None)

    def pack(self, api=1, extra=None, body='__version__ = "4.2.0"\n'):
        path=self.root/'update.zip'
        with zipfile.ZipFile(path,'w') as z:
            z.writestr('mower-android.json',json.dumps({'kind':'mower-android','format':1,'platform':'android','arch':'arm64','runtime_api':api,'python':'3.12','version':'4.2.0','revision':'a'*40}))
            z.writestr('mower/arknights_mower/__init__.py',body)
            z.writestr('mower/server.py','pass')
            z.writestr('mower/ui/dist/index.html','<h1>Mower</h1>')
            z.writestr('mower/requirements.txt','Flask==3.0.3')
            if extra: z.writestr(extra,'pass')
        return path

    def test_inspect_does_not_activate_and_failed_packages_preserve_state(self):
        mower_package.inspect(self.pack())
        self.assertEqual(mower_package.state(),{})
        mower_package.install(self.pack()); previous=mower_package.state()
        for kwargs in ({'api':2},{'extra':'mower/mower_android/launcher.py'},{'extra':'../outside'},{'body':'__version__ = "4.2.0"\nsyntax error!'}):
            with self.subTest(kwargs=kwargs):
                with self.assertRaises((ValueError,SyntaxError)): mower_package.install(self.pack(**kwargs))
                self.assertEqual(mower_package.state(),previous)
        self.assertFalse((self.root.parent/'outside').exists())

    def test_successful_launch_keeps_updated_mower_and_failed_launch_rolls_back(self):
        bundled=self.root/'bundled'
        mower_package.install(self.pack())
        active=mower_package.select_source(bundled)
        self.assertNotEqual(active,bundled)
        self.assertTrue(mower_package.state()['booting'])
        self.assertEqual(mower_package.select_source(bundled),bundled)
        mower_package.install(self.pack())
        active=mower_package.select_source(bundled); mower_package.mark_ready()
        self.assertEqual(mower_package.select_source(bundled),active)
        mower_package.reset(); self.assertEqual(mower_package.select_source(bundled),bundled)

    def test_backups_retire_only_after_the_new_server_is_ready(self):
        bundled=self.root/'bundled'
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# first'))
        first=mower_package.select_source(bundled);mower_package.mark_ready()
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# next'))
        mower_package.mark_ready() # old server cannot commit the pending update
        self.assertTrue(first.exists());self.assertTrue(mower_package.state()['pending'])
        second=mower_package.select_source(bundled)
        self.assertTrue(first.exists())
        mower_package.mark_ready()
        self.assertFalse(first.exists());self.assertTrue(second.exists())
        self.assertNotIn('previous',mower_package.state())
        mower_package.reset();mower_package.mark_ready()
        self.assertTrue(second.exists()) # still serving the old process
        mower_package.select_source(bundled);mower_package.mark_ready()
        self.assertFalse(second.exists())

    def test_failed_launch_keeps_the_previous_version_until_recovery_is_ready(self):
        bundled=self.root/'bundled'
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# first'))
        first=mower_package.select_source(bundled);mower_package.mark_ready()
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# bad'))
        failed=mower_package.select_source(bundled)
        self.assertTrue(first.exists())
        self.assertEqual(mower_package.select_source(bundled),first)
        self.assertTrue(failed.exists())
        mower_package.mark_ready()
        self.assertTrue(first.exists());self.assertFalse(failed.exists())

    def pack_runtime(self, *, minimum=29, python='3.13', corrupt=False):
        original = self.pack()
        with zipfile.ZipFile(original) as z:
            files = {name:z.read(name) for name in z.namelist()}
        payload = b'compressed runtime fixture; extraction is tested by the native installer'
        meta = json.loads(files['mower-android.json'])
        meta.update(format=2, min_apk=minimum, python=python,
                    runtime={'file':'python-runtime.zip.xz', 'sha256':hashlib.sha256(payload).hexdigest(), 'unpacked_size':1024})
        files['mower-android.json'] = json.dumps(meta)
        files['python-runtime.zip.xz'] = payload + (b'corrupt' if corrupt else b'')
        with zipfile.ZipFile(original, 'w') as z:
            for name, contents in files.items(): z.writestr(name, contents)
        return original

    def test_runtime_update_requires_new_host_and_verified_payload(self):
        with patch.dict(os.environ, {'MOWER_APK_CODE':'28'}):
            with self.assertRaisesRegex(ValueError, '最低版本代码 29'):
                mower_package.install(self.pack_runtime())
        with patch.dict(os.environ, {'MOWER_APK_CODE':'29'}):
            meta = mower_package.inspect(self.pack_runtime())
            self.assertEqual(meta['python'], '3.13')
            mower_package.install(self.pack_runtime())
            previous = mower_package.state()
            self.assertTrue((mower_package.folder()/previous['id']/'python-runtime.zip.xz').is_file())
            with self.assertRaisesRegex(ValueError, '校验失败'):
                mower_package.install(self.pack_runtime(corrupt=True))
            self.assertEqual(mower_package.state(), previous)
            with self.assertRaisesRegex(ValueError, '最低版本代码 30'):
                mower_package.inspect(self.pack_runtime(minimum=30))

    def test_native_selection_does_not_roll_back_the_current_boot(self):
        mower_package.install(self.pack())
        ident = mower_package.state()['id']
        mower_package.save({**mower_package.state(), 'booting':True})
        with patch.dict(os.environ, {'MOWER_SOURCE_SELECTED':'1', 'MOWER_ACTIVE_ID':ident}):
            source = mower_package.select_source(self.root/'bundled')
            self.assertEqual(source, mower_package.folder()/ident/'mower')
            self.assertTrue(mower_package.state()['booting'])
            mower_package.mark_ready()
            self.assertNotIn('pending', mower_package.state())
        with patch.dict(os.environ, {'MOWER_SOURCE_SELECTED':'1', 'MOWER_ACTIVE_ID':''}):
            self.assertEqual(mower_package.select_source(self.root/'bundled'), self.root/'bundled')
            self.assertNotIn('MOWER_ACTIVE_ID', os.environ)

    def test_manual_downgrade_requires_confirmation(self):
        from werkzeug.datastructures import FileStorage
        import arknights_mower
        with patch.object(arknights_mower, '__version__', '4.3.0'):
            result=app_update.inspect_upload(FileStorage(stream=io.BytesIO(self.pack().read_bytes()),filename='mower.zip'))
        self.assertTrue(result['downgrade'])
        with self.assertRaises(ValueError): app_update.submit(result['check_id'])
        app_update.submit(result['check_id'],confirm_downgrade=True)
        self.assertTrue(app_update._lock.acquire(timeout=5)); app_update._lock.release()
        self.assertEqual(app_update.status()['status'],'succeeded')
        self.assertEqual(mower_package.state()['version'],'4.2.0')

    def test_shared_update_selects_upstream_mower_zip_not_apk(self):
        asset={'name':'arknights-mower_4.2.0_android_arm64.zip','digest':'sha256:'+'a'*64,'size':10,
               'browser_download_url':'https://github.com/ArkMowers/arknights-mower/releases/download/v4.2.0/arknights-mower_4.2.0_android_arm64.zip'}
        reply=Mock(); reply.json.return_value={'tag_name':'v4.2.0','draft':False,'prerelease':False,'published_at':'2026-09-11T00:00:00Z','assets':[{'name':'mower-android-arm64.apk'},asset],'html_url':'https://github.com/ArkMowers/arknights-mower/releases/tag/v4.2.0'}
        with patch.object(app_update, 'release_index', return_value=None), \
             patch.object(app_update.requests,'get',return_value=reply) as get:
            result=app_update.check('stable')
        self.assertIn('/repos/ArkMowers/arknights-mower/',get.call_args.args[0])
        self.assertEqual(app_update._plans[result['check_id']]['asset'],asset)
        self.assertTrue(result['available'])

    def wait_update(self):
        self.assertTrue(app_update._lock.acquire(timeout=5))
        app_update._lock.release()

    def test_downloaded_update_restarts_only_after_verified_package_is_staged(self):
        contents = self.pack().read_bytes()
        digest = hashlib.sha256(contents).hexdigest()
        asset = {'browser_download_url':'https://example.invalid/mower.zip', 'size':len(contents),
                 'digest':'sha256:'+digest}
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.iter_content.return_value = [contents]
        app_update._plans['online'] = {'asset':asset, 'version':'4.2.0'}

        def restart(method, **params):
            self.prepare_restart.assert_called_once()
            self.assertEqual(method, 'mower_restart')
            current = mower_package.state()
            self.assertEqual(params, {'id':current['id']})
            self.assertTrue(current['pending'])
            self.assertTrue((mower_package.folder()/current['id']/'mower/server.py').is_file())
            saved = json.loads((app_update.restart_folder()/'service-restart.json').read_text())
            self.assertEqual(saved['target'], current['id'])
            self.assertEqual(saved['status'], 'running')
            self.assertEqual(saved['phase'], 'restarting')
            return True

        with patch.dict(os.environ, {'MOWER_SERVICE_RESTART':'1'}), \
                patch.object(app_update.requests, 'get', return_value=response), \
                patch.object(app_update, 'Bridge') as bridge:
            bridge.return_value.call.side_effect = restart
            submitted = app_update.submit('online')
            self.wait_update()
            bridge.return_value.call.assert_called_once()
            self.assertEqual(app_update.status()['status'], 'running')
            with self.assertRaisesRegex(ValueError, '服务正在重启'):
                app_update.submit('another')
            # A new process reads the same journal; only confirmed WebUI readiness completes it.
            with patch.object(app_update, '_job', {'ok':True, 'status':'idle'}):
                self.assertEqual(app_update.status()['id'], submitted['id'])
                active = mower_package.select_source(self.root/'bundled')
                self.assertNotEqual(active, self.root/'bundled')
                self.assertEqual(app_update.status()['status'], 'running')
                mower_package.mark_ready()
                completed = app_update.status()
                self.assertEqual(completed['status'], 'succeeded')
                self.assertTrue(completed['restarted'])
                self.assertIn('4.2.0', completed['message'])
        self.assertFalse((app_update.folder()/(digest+'.zip')).exists())

    def test_invalid_download_never_restarts_the_service(self):
        contents = self.pack().read_bytes()
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.iter_content.return_value = [contents]
        app_update._plans['bad'] = {'asset':{'browser_download_url':'https://example.invalid/mower.zip',
            'size':len(contents), 'digest':'sha256:'+'0'*64}, 'version':'4.2.0'}
        with patch.dict(os.environ, {'MOWER_SERVICE_RESTART':'1'}), \
                patch.object(app_update.requests, 'get', return_value=response), \
                patch.object(app_update, 'Bridge') as bridge:
            app_update.submit('bad'); self.wait_update()
            bridge.assert_not_called()
            self.assertEqual(app_update.status()['status'], 'failed')
        self.assertEqual(mower_package.state(), {})

    def test_manual_update_reports_restart_failure_and_keeps_the_staged_version(self):
        from werkzeug.datastructures import FileStorage
        with patch.dict(os.environ, {'MOWER_SERVICE_RESTART':'1'}), \
                patch.object(app_update, 'Bridge') as bridge:
            bridge.return_value.call.side_effect = RuntimeError('宿主连接已断开')
            preview = app_update.inspect_upload(FileStorage(stream=io.BytesIO(self.pack().read_bytes()),filename='mower.zip'))
            self.assertIn('自动重启', preview['confirm_message'])
            app_update.submit(preview['check_id'], confirm_downgrade=True); self.wait_update()
            result = app_update.status()
            self.assertEqual(result['status'], 'failed')
            self.assertIn('请在手机停止并重新启动服务', result['message'])
            self.assertTrue(mower_package.state()['pending'])
            with patch.object(app_update, '_job', {'ok':True, 'status':'idle'}):
                self.assertEqual(app_update.status()['status'], 'failed')

    def test_failed_new_launch_reports_rollback_after_process_restart(self):
        mower_package.install(self.pack())
        app_update.save_restart({'ok':True, 'id':'restart-job', 'status':'running',
            'target':mower_package.state()['id'], 'version':'4.2.0'})
        mower_package.select_source(self.root/'bundled')
        mower_package.select_source(self.root/'bundled')
        result = app_update.status()
        self.assertEqual(result['status'], 'failed')
        self.assertIn('已恢复原版本', result['message'])

    def test_legacy_host_keeps_manual_restart_and_does_not_call_the_bridge(self):
        from werkzeug.datastructures import FileStorage
        with patch.object(app_update, 'Bridge') as bridge:
            preview = app_update.inspect_upload(FileStorage(stream=io.BytesIO(self.pack().read_bytes()),filename='mower.zip'))
            self.assertIn('停止并重新启动手机服务', preview['confirm_message'])
            app_update.submit(preview['check_id'], confirm_downgrade=True); self.wait_update()
            bridge.assert_not_called()
        self.assertEqual(app_update.status()['status'], 'succeeded')
        self.assertTrue(mower_package.state()['pending'])

    def test_unfinished_task_shutdown_prevents_native_restart(self):
        from werkzeug.datastructures import FileStorage
        self.prepare_restart.side_effect = ValueError('Mower 任务尚未停止，未重启服务')
        with patch.dict(os.environ, {'MOWER_SERVICE_RESTART':'1'}), \
                patch.object(app_update, 'Bridge') as bridge:
            preview = app_update.inspect_upload(FileStorage(stream=io.BytesIO(self.pack().read_bytes()),filename='mower.zip'))
            app_update.submit(preview['check_id'], confirm_downgrade=True); self.wait_update()
            bridge.assert_not_called()
            self.assertEqual(app_update.status()['status'], 'failed')
            self.assertIn('任务尚未停止', app_update.status()['message'])

    def test_python_interface_update_does_not_restart_the_service(self):
        from mower_android import python_package
        from werkzeug.datastructures import FileStorage
        code = (Path(__file__).resolve().parents[1]/'runtime/mower_android/maa_adapter.py').read_bytes()
        contents = io.BytesIO()
        meta = {'kind':'mower-maa-python', 'format':1, 'version':'1.0.0', 'channel':'stable',
                'bridge_protocol':1, 'min_apk':5, 'sha256':hashlib.sha256(code).hexdigest()}
        with zipfile.ZipFile(contents, 'w') as archive:
            archive.writestr('maa-python.json', json.dumps(meta))
            archive.writestr('maa.py', code)
        with patch.dict(os.environ, {'MOWER_SERVICE_RESTART':'1', 'MOWER_APK_CODE':'29'}), \
                patch.object(python_package, 'ROOT', self.root/'python'), \
                patch.object(app_update, 'Bridge') as bridge:
            preview = app_update.inspect_upload(FileStorage(stream=io.BytesIO(contents.getvalue()),filename='python.zip'))
            app_update.submit(preview['check_id']); self.wait_update()
            bridge.assert_not_called()
            self.prepare_restart.assert_not_called()
            self.assertEqual(app_update.status()['status'], 'succeeded')

    def ready_restart(self, *, resume=True):
        mower_package.install(self.pack())
        target = mower_package.state()['id']
        app_update.save_restart({'ok':True, 'id':'restart-job', 'status':'running', 'phase':'restarting',
            'target':target, 'version':'4.2.0', 'resume_mower':resume})
        mower_package.select_source(self.root/'bundled')
        mower_package.mark_ready()
        return target

    def test_restart_journal_uses_the_stable_host_update_cache(self):
        self.assertEqual(app_update.restart_folder(), self.root/'tmp/updates/4e21008231518eabb448/android-service')

    def test_resume_waits_for_readiness_and_starts_saved_queue_only_once(self):
        self.ready_restart()
        server = Mock()
        server.start.__wrapped__ = Mock(return_value='true')
        with patch.dict(os.environ):
            app_update.restore_restart_context()
            self.assertEqual(os.environ['MOWER_RESTART_JOB'], 'restart-job')
        self.assertEqual(app_update.status()['status'], 'running')
        mower_package.save({**mower_package.state(), 'pending':True, 'booting':True})
        app_update.resume_tasks(server)
        server.start.__wrapped__.assert_not_called()
        mower_package.mark_ready()
        app_update.resume_tasks(server)
        app_update.resume_tasks(server)
        server.start.__wrapped__.assert_called_once_with('0')
        self.assertEqual(app_update.status()['status'], 'succeeded')
        self.assertIn('已续接原任务', app_update.status()['message'])

    def test_stopped_tasks_and_user_cancellation_do_not_resume(self):
        for was_running, cancellation in ((False, ''), (True, 'restart-job')):
            with self.subTest(was_running=was_running):
                self.ready_restart(resume=was_running)
                (app_update.restart_folder()/'service-restart-cancelled').write_text(cancellation)
                server = Mock()
                server.start.__wrapped__ = Mock()
                app_update.resume_tasks(server)
                server.start.__wrapped__.assert_not_called()
                self.assertEqual(app_update.status()['status'], 'succeeded')

    def test_resume_failure_and_uncertain_previous_attempt_are_not_replayed(self):
        for previous in ('', 'starting'):
            with self.subTest(previous=previous):
                self.ready_restart()
                job = app_update.read_restart()
                job['resume_state'] = previous
                app_update.save_restart(job)
                server = Mock()
                server.start.__wrapped__ = Mock(return_value='false')
                app_update.resume_tasks(server)
                app_update.resume_tasks(server)
                self.assertEqual(server.start.__wrapped__.call_count, 0 if previous else 1)
                self.assertEqual(app_update.status()['status'], 'failed')

    def test_previous_job_cancellation_does_not_cancel_the_current_resume(self):
        self.ready_restart()
        (app_update.restart_folder()/'service-restart-cancelled').write_text('previous-job')
        server = Mock()
        server.start.__wrapped__ = Mock(return_value='true')
        app_update.resume_tasks(server)
        server.start.__wrapped__.assert_called_once_with('0')

    def test_webui_stop_cancels_resume_before_the_old_process_exits(self):
        self.ready_restart()
        app_update.cancel_resume()
        server = Mock()
        server.start.__wrapped__ = Mock()
        app_update.resume_tasks(server)
        server.start.__wrapped__.assert_not_called()
        self.assertEqual(app_update.status()['status'], 'succeeded')

    def test_resume_loads_persisted_tasks_with_their_original_times(self):
        import ast
        import importlib.util
        import threading
        from datetime import datetime
        from types import SimpleNamespace
        from arknights_mower import utils
        record_path = Path(__file__).resolve().parents[1]/'runtime/arknights_mower/solvers/record.py'
        spec = importlib.util.spec_from_file_location('restart_record_fixture', record_path)
        record = importlib.util.module_from_spec(spec)
        isolated_config = Mock()
        with patch.dict(sys.modules, {'arknights_mower.utils.config':isolated_config,
                'arknights_mower.utils.log':SimpleNamespace(logger=Mock())}), \
                patch.object(utils, 'config', isolated_config, create=True):
            spec.loader.exec_module(record)
        saved = {'tasks':[
            {'type':'run_order', 'time':datetime(2026, 10, 19, 15, 30), 'meta_data':'room_2_1'},
            {'type':'skill_upgrade', 'time':datetime(2026, 10, 19, 16, 30), 'meta_data':'plan_key=1'}]}
        self.ready_restart()
        source = Path(__file__).resolve().parents[1]/'runtime/server.py'
        # Run the production starter without importing the server's background workers.
        starter = next(node for node in ast.parse(source.read_text()).body if isinstance(node, ast.FunctionDef) and node.name == 'start')
        starter.decorator_list = []
        namespace = dict(mower_thread=None, active_job=lambda:False, maa_maintenance_lock=threading.RLock(),
            _job_running=lambda job:False, maa_update_job={}, maa_resource_update_job={},
            resource_update=SimpleNamespace(running=lambda:False), get_path=lambda name:self.root,
            config=Mock(), load_state=record.load_state, Thread=Mock(), set_mower_thread=Mock(), log_stream=Mock())
        exec(compile(ast.Module(body=[starter], type_ignores=[]), str(source), 'exec'), namespace)
        server = SimpleNamespace(start=SimpleNamespace(__wrapped__=namespace['start']))
        with patch.object(record, 'get_path', side_effect=lambda name:self.root/'data.db' if name.endswith('data.db') else self.root), \
                patch.object(record, '_tables_created', False), \
                patch.dict(sys.modules, {'arknights_mower.__main__':SimpleNamespace(main=Mock())}):
            self.assertTrue(record.save_state_to_db(saved))
            app_update.resume_tasks(server)
            resumed, refresh = namespace['Thread'].call_args.kwargs['args']
            self.assertEqual(resumed, saved)
            self.assertFalse(refresh)
            self.assertEqual(app_update.status()['status'], 'succeeded')


class RestartPreparationTests(unittest.TestCase):
    def setUp(self):
        from arknights_mower import utils
        config = Mock()
        modules = patch.dict(sys.modules, {'arknights_mower.utils.config':config,
            'arknights_mower.solvers.record':Mock()})
        modules.start(); self.addCleanup(modules.stop)
        attribute = patch.object(utils, 'config', config, create=True)
        attribute.start(); self.addCleanup(attribute.stop)

    def test_running_tasks_are_saved_before_restart_and_save_failure_aborts_it(self):
        import threading
        from arknights_mower.utils import config
        from arknights_mower.solvers import record
        server = Mock()
        server.maa_maintenance_lock = threading.RLock()
        server.mower_thread.is_alive.return_value = True
        server._job_running.return_value = False
        server.resource_update.running.return_value = False
        server.stop.__wrapped__ = Mock(return_value='true')
        saved = {'tasks':[{'type':'run_order', 'time':'2026-10-19T15:30:00'}]}
        with patch.dict(sys.modules, {'server':server}), patch.object(config, 'stop_maa'), \
                patch.object(record, 'current_state', return_value=saved), \
                patch.object(record, 'save_state_to_db', return_value=True) as persist:
            self.assertTrue(app_update.prepare_restart())
            persist.assert_called_once_with(saved)
            persist.return_value = False
            with self.assertRaisesRegex(ValueError, '任务状态保存失败'):
                app_update.prepare_restart()

    def test_preparation_reuses_saved_shutdown_and_aborts_maa(self):
        import threading
        from arknights_mower.utils import config
        server = Mock()
        server.mower_thread = None
        server.maa_maintenance_lock = threading.RLock()
        server._job_running.return_value = False
        server.resource_update.running.return_value = False
        server.stop.__wrapped__ = Mock(return_value='true')
        with patch.dict(sys.modules, {'server':server}), patch.object(config, 'stop_maa') as stopped:
            app_update.prepare_restart()
            stopped.set.assert_called_once()
            server.stop.__wrapped__.assert_called_once()
            server.stop.assert_not_called()

    def test_busy_components_and_failed_shutdown_prevent_restart_preparation(self):
        import threading
        from arknights_mower.utils import config
        server = Mock()
        server.mower_thread = None
        server.maa_maintenance_lock = threading.RLock()
        server._job_running.return_value = True
        server.stop.__wrapped__ = Mock(return_value='false')
        with patch.dict(sys.modules, {'server':server}), patch.object(config, 'stop_maa') as stopped:
            with self.assertRaisesRegex(ValueError, '其他组件正在更新'):
                app_update.prepare_restart()
            stopped.set.assert_not_called()
            server.stop.__wrapped__.assert_not_called()
            server._job_running.return_value = False
            server.resource_update.running.return_value = False
            with self.assertRaisesRegex(ValueError, '任务尚未停止'):
                app_update.prepare_restart()
            server.stop.__wrapped__.assert_called_once()


if __name__=='__main__': unittest.main()
