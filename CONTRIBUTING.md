# Contributing

Thank you for your interest in FyrQL.

## Issues

Issues are welcome: a bug with the version, the statement or code that shows it and what
happened instead, a question, or the wish for a feature or a source. A vulnerability goes
not into an issue but through private reporting, as [SECURITY.md](SECURITY.md) describes.

## Pull requests

Please open an issue first and agree there on what a pull request should change, before
you write it: a change that was not agreed on may not fit the plans for FyrQL, and would
then be work in vain. A pull request should

- be about one thing, with tests for what it changes;
- build and pass the tests with `./mvnw -B -ntp verify`, on Java 21 or later;
- keep to the style of the code around it;
- update the [manual](docs/manual.md), and the [README](README.md) or
  [getting started](docs/getting-started.md) where they describe what it changes.

[Building and testing](docs/manual.md#building-and-testing) describes the tests, the
databases they use and the profiles of the build.

## License

FyrQL is licensed under the Mozilla Public License 2.0. By submitting a contribution, you
agree that it is licensed under the same license, and you confirm that you have the right
to submit it under it.
