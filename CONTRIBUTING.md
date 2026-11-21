# Contributing to Loquilis

Thank you for looking. Loquilis is early, so the most useful thing right now is an issue: a bug, an agent whose events come through wrong, or a view that is hard to read. Pull requests are welcome too. For anything bigger than a fix, please open an issue first, so we can agree on the shape before you build it.

## Building and testing

You need JDK 17 or newer. From the repository root:

```
./gradlew :loquilis-model:jvmTest :loquilis-agents:jvmTest :loquilis-store:jvmTest :loquilis-views:jvmTest :app:jvmTest
```

CI runs the same tests on every push and pull request, plus the schema checks below.

## Rules the code keeps

- **The conversation model is defined in protobuf** (`loquilis-model/protobuf/`). The Kotlin generated from it is committed and never edited by hand: change the `.proto`, then run `./gradlew :loquilis-model:generateCommonMainProtos`. CI fails when the two disagree.
- **Schema changes stay compatible** with conversations already on disk. CI runs `buf lint` and `buf breaking`.
- **Loquilis depends on nothing in Tutilis.** The one shared piece, the ui-kit, depends on Compose only. See `doc/decisions/0001-dependency-direction.md`.
- **The library stays first.** Behavior belongs in the modules, with `app` kept thin, so that someone building their own console can reuse it.

## License

Loquilis is MIT-licensed (see [LICENSE](LICENSE)). By contributing, you agree that your contribution is licensed the same way.
