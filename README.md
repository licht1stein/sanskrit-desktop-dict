# Sanskrit Dictionaries by MB

A Desktop version of [Cologne Digital Sanskrit Dictionaries](https://www.sanskrit-lexicon.uni-koeln.de/)

![Screenshot 01](/screenshots/01.png)

## Using the app

You can download latest version from our [Telegram Channel](https://t.me/sanskritdesktop). The `pkg` file is not signed, since this is a very niche non-commercial product. So in order to install it you need to:

1. Download the `pkg` file from Telegram.
2. Right-click the downloaded file and click Open.
3. Agree to install from untrusted source.

## Development

Run the project directly, via `:exec-fn`:

    $ clojure -X:run-x
    Hello, Clojure!

Run the project's tests (they'll fail until you edit them):

    $ clojure -T:build test

Run the project's CI pipeline and build an uberjar:

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the uberjar in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

If you don't want the `pom.xml` file in your project, you can remove it. The `ci` task will
still generate a minimal `pom.xml` as part of the `uber` task, unless you remove `version`
from `build.clj`.

Run that uberjar:

    $ java -jar target/sanskrit-desktop-dict-0.1.0-SNAPSHOT.jar

If you remove `version` from `build.clj`, the uberjar will become `target/sanskrit-desktop-dict-standalone.jar`.

Run the packaging tool to produce a Mac pkg file (requires the uberjar to be already in target/ directory):

    $ clojure -T:build mac

Bump version:

    $ clojure -T:build version :bump :patch
	$ clojure -T:build version :bump :minor
	$ clojure -T:build version :bump :major
