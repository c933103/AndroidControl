#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Retain only target-app crash logger PIDs from a bounded fresh-emulator buffer."""
import re
import sys
lines = sys.stdin.read().splitlines()
identity = re.compile(r'(?:Process: org\.androidcontrol\.app, PID:|>>> org\.androidcontrol\.app <<<)')
prefix = re.compile(r'^\w/[^ (]+\s*\(\s*(\d+)\):')
pids = {m.group(1) for line in lines if identity.search(line) if (m := prefix.match(line))}
retained = [line for line in lines if (m := prefix.match(line)) and m.group(1) in pids]
print('\n'.join(retained) if retained else 'No target-app crash record in bounded crash buffer.')
