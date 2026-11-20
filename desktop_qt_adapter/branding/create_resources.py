#!/usr/bin/env python3
"""Generate a test resource map without overwriting the pinned upstream tree.

Only the known wordmark is replaced and an unsupported branded spinner is
excluded. All other resources retain upstream attribution and need a separate
rights review; this does not make the application ready for distribution.
"""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--logo', required=True, type=Path)
    parser.add_argument('--font', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    logo = args.logo.resolve(strict=True)
    root = ET.parse(source).getroot()
    if root.tag != 'RCC':
        raise ValueError('not a Qt resource file')
    replaced = removed = 0
    seen = set()
    for resource in root.findall('qresource'):
        if resource.get('prefix', '/') != '/':
            raise ValueError('unexpected resource prefix')
        for entry in list(resource):
            if entry.tag != 'file' or not entry.text:
                raise ValueError('unexpected resource entry')
            name = entry.text.strip().replace('\\', '/')
            alias = entry.get('alias', name)
            if alias in seen:
                raise ValueError('duplicate resource alias: ' + alias)
            seen.add(alias)
            path = (source.parent / name).resolve(strict=True)
            if not path.is_relative_to(source.parent):
                raise ValueError('resource escapes source directory')
            if alias == 'gif/windscribe_spinner.gif':
                resource.remove(entry)
                removed += 1
                continue
            if alias == 'svg/LOGO.svg':
                path = logo
                replaced += 1
            entry.set('alias', alias)
            entry.text = path.as_posix()
    if replaced != 1 or removed != 1:
        raise ValueError('upstream branding resources changed; review before continuing')
    # FontManager references this alias from a separate upstream resource map.
    # Include only the font, not the upstream application/tray icon set. This
    # development resource is not an assertion of redistribution rights.
    font_alias = 'resources/fonts/ibmplexsans.ttf'
    if font_alias in seen:
        raise ValueError('font alias already exists; review the upstream resource map')
    font = args.font.resolve(strict=True)
    if not font.is_relative_to(source.parent):
        raise ValueError('font escapes upstream GUI directory')
    font_entry = ET.SubElement(root.find('qresource'), 'file', alias=font_alias)
    font_entry.text = font.as_posix()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root)
    ET.ElementTree(root).write(args.output, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    main()
