import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError, HTTPError

import campus_core as core
import windows_support as windows
import campus_login as app

OFFLINE = {"result": 0, "v46ip": "172.21.5.27", "ss4": "000000000000"}
INFO = core.PortalInfo("172.21.5.27", "000000000000", 1, "3.3.2", "", core.AC_IP, "")
CONFIG = '''var pageSetting=2;
ipPageAry[0]="default|0.0.0.0|255.255.255.255|||1|1|";
ipPageAry[1]="wired|172.18.224.1;172.31.0.1|172.24.255.255;172.31.3.254|||1|1|admin";
ipPageAry[2]="disabled|172.21.0.0|172.21.255.255|||0|8|admin";
ipPageAry[3]="other|10.4.1.2|10.4.1.254|||1|8|admin";
'''


class ParsingTests(unittest.TestCase):
    def test_jsonp_plain_and_callback(self):
        for text in (' {"result":1} ', ' dr1001({"result":1}) ; '):
            self.assertEqual(core.parse_jsonp(text), {"result": 1})

    def test_untrusted_response_is_not_executed(self):
        for text in ('alert(1)', 'dr1001({"result":0});alert(1)', '[]', 'null'):
            with self.assertRaises(core.TemporaryError):
                core.parse_jsonp(text)

    def test_gzip_and_gbk(self):
        text = '校园网认证页'
        self.assertEqual(core.decode_body(gzip.compress(text.encode('gbk')), 'gbk'), text)

    def test_script_object_properties(self):
        script = "var page={name:'20180816'}; var jsVersion='3.3.2';"
        self.assertEqual(core.js_string(script, 'name'), '20180816')
        self.assertEqual(core.js_string(script, 'jsVersion'), '3.3.2')

    def test_fresh_ip_and_invalid_ip(self):
        self.assertEqual(core.current_ip(OFFLINE), '172.21.5.27')
        self.assertEqual(core.current_ip({'v46ip': '0.0.0.0', 'ss5': '172.21.1.5'}), '172.21.1.5')
        for address in ('0.0.0.0', '127.0.0.1', '169.254.0.1', '8.8.8.8'):
            with self.assertRaises(core.TemporaryError):
                core.current_ip({'v46ip': address})

    def test_current_wired_method(self):
        self.assertEqual(core.select_method(CONFIG, '172.21.5.27'), 1)
        self.assertEqual(core.select_method(CONFIG, '172.31.1.7'), 1)
        with self.assertRaises(core.CampusError):
            core.select_method(CONFIG, '10.4.1.2')

    def test_suffix(self):
        self.assertEqual(core.account_with_suffix('student', '校园用户'), 'student@xyw')
        self.assertEqual(core.account_with_suffix('student@dx', '校园电信'), 'student@dx')
        self.assertEqual(core.account_with_suffix('student', '校园移动'), 'student')


class LoginTests(unittest.TestCase):
    def setUp(self):
        self.client = core.CampusClient()

    def test_online_skips_credentials_and_login(self):
        with patch.object(self.client, 'status', return_value={'result': 1}), patch.object(self.client, 'get') as get:
            self.assertIn('无需重复', self.client.ensure_online('', '', '校园用户'))
            get.assert_not_called()

    def test_unknown_status_never_submits_credentials(self):
        with patch.object(self.client, 'status', return_value={}), patch.object(self.client, 'get') as get:
            with self.assertRaises(core.TemporaryError):
                self.client.ensure_online('student', 'fake password', '校园用户')
            get.assert_not_called()

    def test_login_parameters_and_verification(self):
        with patch.object(self.client, 'status', side_effect=[OFFLINE, {'result': 1}]), \
             patch.object(self.client, 'discover', return_value=INFO), \
             patch.object(self.client, 'get', return_value='dr1003({"result":1})') as get, \
             patch('campus_core.time.sleep'):
            self.assertIn('已确认在线', self.client.ensure_online('student', 'fake &+=密码', '校园电信'))
            url, data = get.call_args.args
            self.assertEqual(url, core.EPORTAL)
            self.assertEqual(data['wlan_user_ip'], '172.21.5.27')
            self.assertEqual(data['user_account'], 'student@dx')
            self.assertEqual(data['user_password'], 'fake &+=密码')
            self.assertEqual(data['a'], 'login')

    def test_already_online_code_requires_confirmation(self):
        for confirmed in (True, False):
            states = [OFFLINE] + [{'result': 1 if confirmed else 0}] * 3
            with patch.object(self.client, 'status', side_effect=states), \
                 patch.object(self.client, 'discover', return_value=INFO), \
                 patch.object(self.client, 'get', return_value='dr1003({"result":0,"ret_code":2})'), \
                 patch('campus_core.time.sleep'):
                if confirmed:
                    self.assertIn('已确认在线', self.client.ensure_online('student', 'fake', '校园用户'))
                else:
                    with self.assertRaises(core.TemporaryError):
                        self.client.ensure_online('student', 'fake', '校园用户')

    def test_rejection_is_fatal_and_message_redacted(self):
        with patch.object(self.client, 'status', return_value=OFFLINE), \
             patch.object(self.client, 'discover', return_value=INFO), \
             patch.object(self.client, 'get', return_value='{"result":0,"ret_code":1,"msg":"fake-secret"}'):
            with self.assertRaises(core.AuthenticationError) as raised:
                self.client.ensure_online('student', 'fake-secret', '校园用户')
            self.assertNotIn('fake-secret', str(raised.exception))

    def test_discover_ignores_old_url_ip(self):
        page = "<!--Dr.COMWebLoginID_0.htm-->v46ip='172.21.5.27';ss4='000000000000';var fileVersion='123';"
        script = "var page={name:'20180816'};var jsVersion='3.3.2';var accountPrefix=0;"
        with patch.object(self.client, 'get', side_effect=[page, script, CONFIG]) as get:
            info = self.client.discover(OFFLINE)
            self.assertEqual(info.address, '172.21.5.27')
            self.assertNotIn('wlanuserip', get.call_args_list[0].args[0])

    def test_ip_change_prevents_authentication(self):
        page = "<!--Dr.COMWebLoginID_0.htm-->v46ip='172.21.5.28';"
        with patch.object(self.client, 'get', return_value=page):
            with self.assertRaises(core.TemporaryError):
                self.client.discover(OFFLINE)

    def test_http_errors_never_leak_credential_url(self):
        for exc in (URLError('http://example/?user_password=fake-secret'),
                    HTTPError('http://example/?user_password=fake-secret', 302, 'redirect', {}, io.BytesIO())):
            with patch.object(self.client.opener, 'open', side_effect=exc):
                with self.assertRaises(core.TemporaryError) as raised:
                    self.client.get(core.EPORTAL, {'user_password': 'fake-secret'})
                self.assertNotIn('fake-secret', str(raised.exception))

    def test_credential_redirects_blocked(self):
        handler = core.NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, '', {}, 'http://example.com'))
        with self.assertRaises(core.CampusError):
            self.client.get('http://example.com', {'user_password': 'fake-secret'})


class WindowsTests(unittest.TestCase):
    def test_dpapi_round_trip_and_ciphertext(self):
        secret = 'fake-account:f@ke 中文密码'.encode('utf-8')
        encrypted = windows.crypt(secret)
        self.assertNotIn(secret, encrypted)
        self.assertEqual(windows.crypt(encrypted, True), secret)

    def test_config_round_trip_without_plaintext(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
            data = Path(directory)
            path = data / 'settings.json'
            with patch.object(windows, 'DATA', data), patch.object(windows, 'SETTINGS', path):
                windows.save_credentials('fake-account', 'fake-secret', '校园用户')
                self.assertEqual(windows.load_credentials(), ('fake-account', 'fake-secret', '校园用户'))
                contents = path.read_text('utf-8')
                self.assertNotIn('fake-account', contents)
                self.assertNotIn('fake-secret', contents)

    def test_mutex_excludes_other_thread(self):
        import threading
        first = windows.Mutex('unittest')
        self.assertTrue(first.acquire())
        results = []

        def try_acquire():
            second = windows.Mutex('unittest')
            results.append(second.acquire())
            second.close()

        try:
            thread = threading.Thread(target=try_acquire)
            thread.start()
            thread.join()
            self.assertEqual(results, [False])
        finally:
            first.close()

    def test_startup_command_quotes_path_with_spaces(self):
        # 不依赖当前 checkout 的目录是否恰好含空格（例如 GitHub Actions）。
        with patch.object(windows, 'ROOT', Path('C:/Program Files/AHU Campus')):
            command = windows.launch_command()
            self.assertIn('"' + str(windows.ROOT / 'campus_login.py') + '"', command)
            self.assertTrue(command.endswith('--watch'))


class WatcherTests(unittest.TestCase):
    def test_rejected_credentials_are_not_repeated(self):
        with patch.object(app, 'Mutex') as mutex, \
             patch.object(app, 'make_logger'), \
             patch.object(app, 'startup_enabled', side_effect=[True] * 6 + [False]), \
             patch.object(app, 'SETTINGS') as settings, \
             patch.object(app, 'connect_once', side_effect=core.AuthenticationError('rejected')) as connect, \
             patch.object(app.time, 'sleep'):
            mutex.return_value.acquire.return_value = True
            settings.stat.return_value.st_mtime_ns = 1
            self.assertEqual(app.watch(), 0)
            connect.assert_called_once()

    def test_changed_config_resumes_after_rejection(self):
        with patch.object(app, 'Mutex') as mutex, \
             patch.object(app, 'make_logger'), \
             patch.object(app, 'startup_enabled', side_effect=[True] * 5 + [False]), \
             patch.object(app, 'SETTINGS') as settings, \
             patch.object(app, 'connect_once', side_effect=[core.AuthenticationError('rejected'), 'online']) as connect, \
             patch.object(app.time, 'sleep'):
            mutex.return_value.acquire.return_value = True
            first, second = unittest.mock.Mock(), unittest.mock.Mock()
            first.st_mtime_ns, second.st_mtime_ns = 1, 2
            settings.stat.side_effect = [first, second]
            self.assertEqual(app.watch(), 0)
            self.assertEqual(connect.call_count, 2)


if __name__ == '__main__':
    unittest.main()
