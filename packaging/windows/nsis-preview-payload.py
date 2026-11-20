#!/usr/bin/env python3
"""Write exact NSIS uninstall paths for a private CI-staged preview payload."""
import argparse
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('staging', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    files, directories = [], []
    for path in sorted(args.staging.rglob('*')):
        name = path.relative_to(args.staging)
        for part in name.parts:
            # No NSIS substitution, alternative streams, or path separators.
            if not re.fullmatch(r'[A-Za-z0-9_. +@()-]+', part) or part.endswith(('.', ' ')):
                parser.error(f'Invalid Windows payload component: {part!r}')
        if path.is_symlink() or (not path.is_dir() and not path.is_file()):
            parser.error(f'Not a regular payload entry: {name}')
        if str(name).lower() == 'uninstall.exe':
            parser.error('Uninstall.exe is reserved')
        (directories if path.is_dir() else files).append('\\'.join(name.parts))
    if not files:
        parser.error('Empty Windows payload')
    commands = [f'Delete "$INSTDIR\\{name}"' for name in files]
    # RMDir can legitimately fail for directories with files added by the user.
    commands += ['${IfNot} ${Errors}']
    commands.extend(f'  RMDir "$INSTDIR\\{name}"' for name in reversed(directories))
    commands += ['  ClearErrors', '${EndIf}']
    args.output.write_text('\n'.join(commands) + '\n', encoding='ascii')


if __name__ == '__main__':
    main()
