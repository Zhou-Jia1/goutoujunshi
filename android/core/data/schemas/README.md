# Room schema baseline

`JunshiDatabase` exports schema version 1 through the existing KSP `room.schemaLocation` option.
Generate the authoritative JSON using `./gradlew :core:data:kspDebugKotlin` and commit
`com.shengjidaguai.goutoujunshi.data.JunshiDatabase/1.json` before claiming the baseline verified.
No handwritten JSON or fictitious historical migration is supplied.

The data module's instrumentation assets must include this directory. `SchemaBaselineTest`
uses Room's `MigrationTestHelper` to open the exported V1 and validate it with no migrations.
When changing version, add a real migration to `JunshiDatabase.MIGRATIONS` and add a fixture
test retaining representative rows across the transition; never use destructive fallback.

`RepositoryTest` uses an unencrypted in-memory Room database only for relational and policy
tests. `SqlCipherDeviceTest` separately opens a real SQLCipher file, checks its header,
reopens with the correct key and rejects the wrong key. It also tests Keystore wrappers and
generation invalidation. JVM tests never initialize AndroidKeyStore or the production factory.

At authoring time Gradle dependencies were unavailable; neither JSON export nor Android tests
has been claimed as run. The parent task coordinates the build and records actual results.
