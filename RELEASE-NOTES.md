# Release notes for java-watch

## 0.5.0 - 2025-04-15 - first release

First version of java-watch. Included features:

- Monitor changes at:
  - single entry level
  - directory level (only direct descendants)
  - all descendants level
- Handle `OVERFLOW` events:
  - Yourself
  - We generate events for all current entries on disk
  - We keep track of changes in a map, and based on modification stamp generate a most likely events
- extensive tests for Linux/Windows/OSX support
