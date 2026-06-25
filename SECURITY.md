# Security Policy

## Reporting a vulnerability

Found something that looks like a security problem? Don't open a public issue for
it — email **thomas@bom.best** instead. Put "EP-133 Sample Tool security" in the
subject so it doesn't get lost.

Helpful things to include:

- what you found and where (file, platform, version)
- how to reproduce it
- what an attacker could actually do with it

I'll confirm I got it within a few days and keep you in the loop while it's
sorted. Once there's a fix, I'm happy to credit you — or keep you anonymous,
your call.

## Scope

This tool talks to hardware you own over USB-MIDI and runs fully offline — there's
no server, no account, no telemetry. The most relevant surface is the bundled web
app and the native MIDI bridges. Reports about the bundled Teenage Engineering
assets under `data/` should go to TE, not here (see [NOTICE](NOTICE)).

## Supported versions

This is a community project, so support tracks the latest release on `main`. Fixes
land there first; older builds aren't separately patched.
