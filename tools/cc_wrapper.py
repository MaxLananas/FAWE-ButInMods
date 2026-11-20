#!/usr/bin/env python3
# Drop `-fuse-ld=*` (see cc-wrapper.sh) and exec the system cc.
import os
import sys

argv = [a for a in sys.argv[1:] if not a.startswith('-fuse-ld=')]
os.execv('/usr/bin/cc', ['/usr/bin/cc'] + argv)
