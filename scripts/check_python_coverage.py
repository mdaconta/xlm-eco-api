"""Run all handwritten Python suites and enforce separate statement/branch gates."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    os.chdir(ROOT)
    (ROOT / 'target').mkdir(exist_ok=True)
    environment = os.environ.copy()
    environment['PYTHONPATH'] = os.pathsep.join([
        str(ROOT), str(ROOT / 'python_client' / 'generated'),
        environment.get('PYTHONPATH', '')])
    commands = [
        ['erase'],
        ['run', '-m', 'unittest', 'discover', '-s', 'gui/chat-ui', '-p', 'test_*.py'],
        ['run', '--append', '-m', 'unittest', 'discover', '-s', 'src/test/python', '-p', 'test_*.py'],
        ['report'], ['json'], ['xml'], ['html'],
    ]
    for command in commands:
        subprocess.run([sys.executable, '-m', 'coverage', *command], env=environment, check=True)
    report = json.loads((ROOT / 'target/python-coverage.json').read_text())
    totals = report['totals']
    failed = False
    for label, covered, count in [
        ('statements', totals['covered_lines'], totals['num_statements']),
        ('branches', totals['covered_branches'], totals['num_branches']),
    ]:
        percentage = 100 * covered / count if count else 100
        print(f'Python {label}: {covered}/{count} ({percentage:.2f}%), required >=90%')
        failed |= covered * 100 < count * 90
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
