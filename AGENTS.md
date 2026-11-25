# Working on Ryzn

Start with the [master index](docs/INDEX.md) to find the owner of a feature and the API it exposes.

- Keep Minecraft and Fabric entry points thin. Put feature behavior in the owning package and route
  cross-feature calls through the documented service or event.
- Keep saved keys, defaults, lifecycle order, and coordinate conventions compatible. Add a migration
  and a fixture when a saved format changes.
- Put tests in the owning Java package under `src/test/java`. Cover state transitions, cancellation,
  and retry behavior when editing shared services.
- Use comments to record contracts, units, threading, or why an ordering matters. Update the index
  when an entry point or package owner changes.
- Follow the [model asset rules](docs/INDEX.md#data-and-model-assets) when editing ABCurves or adding models.
