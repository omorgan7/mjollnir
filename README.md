# Mjollnir

A simple program to read out end game state of a Dota 2 replay.

## Usage

```sh
mvn package
java -jar targets/mjollnir replay_path
```

And a json file containing end game state will be written to the current working directory.

## License
MIT

## Credits
Skadistats [clarity](https://github.com/skadistats/clarity), which this library is dependent on.