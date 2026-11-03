import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('bundle_versions', Path(__file__).resolve().parents[1] / 'verify_bundled_runtime_versions.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BundleVersionTest(unittest.TestCase):
    def test_current_profile_is_accepted(self):
        module.validate_profile({'type': 'Proton', 'versionName': 'proton-15', 'versionCode': 15}, 'proton-15', 15, 'Proton', 'runtime.wcp')

    def test_stale_code_name_and_type_are_rejected_independently(self):
        for field, old in [('versionCode', 14), ('versionName', 'proton-14'), ('type', 'DXVK')]:
            profile = {'type': 'Proton', 'versionName': 'proton-15', 'versionCode': 15}
            profile[field] = old
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'expected Proton proton-15 / code 15'):
                module.validate_profile(profile, 'proton-15', 15, 'Proton', 'runtime.wcp')


if __name__ == '__main__':
    unittest.main()
