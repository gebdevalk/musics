# src/common/data/semantics.py
"""
Semantic lookup table — short-name to long-name parameter mappings
and named constant presets (dynamics, tempo, panning, styles, etc.).

Pure nested dict literals. No classes, no mutation.
"""

SEMANTIC_TABLE = {
  "parameters": {
    "a": "articulation", "articulation": "articulation",
    "c": "conformity", "conformity": "conformity",
    "d": "density", "density": "density",
    "e": "expression", "expression": "expression",
    "g": "groove", "groove": "groove",
    "h": "humanization", "humanization": "humanization",
    "i": "instrument", "instrument": "instrument",
    "j": "jitter", "jitter": "jitter",
    "l": "location", "location": "location",
    "m": "micro", "micro": "micro",
    "o": "octave", "octave": "octave",
    "p": "panning", "panning": "panning",
    "q": "quantize", "quantize": "quantize",
    "r": "rate", "rate": "rate",
    "s": "swing", "swing": "swing",
    "t": "transposition", "transposition": "transposition",
    "u": "durScale", "durScale": "durScale",
    "v": "volume", "volume": "volume",
    "w": "window", "window": "window",
    "x": "expression2", "expression2": "expression2",
    "C": "Chord", "Chord": "Chord",
    "D": "delay", "delay": "delay",
    "F": "Form", "Form": "Form",
    "G": "GlobalGroove", "GlobalGroove": "GlobalGroove",
    "H": "Harmony", "Harmony": "Harmony",
    "K": "key", "key": "key",
    "L": "Layout", "Layout": "Layout",
    "M": "meter", "meter": "meter",
    "O": "Orchestration", "Orchestration": "Orchestration",
    "P": "Phrase", "Phrase": "Phrase",
    "Q": "Quantize", "Quantize": "Quantize",
    "R": "reverb", "reverb": "reverb",
    "S": "Scale", "Scale": "Scale",
    "T": "tempo", "tempo": "tempo",
    "V": "Voice", "Voice": "Voice",
    "W": "Width", "Width": "Width"
  },

  "constants": {
    "!silence":   { "volume": 0 },
    "!pppp":      { "volume": 10 },
    "!ppp":       { "volume": 20 },
    "!pp":        { "volume": 30 },
    "!p":         { "volume": 40 },
    "!mp":        { "volume": 50 },
    "!mf":        { "volume": 60 },
    "!f":         { "volume": 70 },
    "!ff":        { "volume": 80 },
    "!fff":       { "volume": 90 },
    "!ffff":      { "volume": 100 },

    "!sfz":       { "accent": "sfz" },
    "!fp":        { "accent": "fp" },
    "!cresc":     { "volumeEnvelope": "crescendo" },
    "!decresc":   { "volumeEnvelope": "decrescendo" },
    "!dim":       { "volumeEnvelope": "decrescendo" },

    "!left":      { "panning": -1.0 },
    "!center":    { "panning": 0.0 },
    "!right":     { "panning": 1.0 },

    "!near":      { "reverb": 0.1, "width": 0.8 },
    "!far":       { "reverb": 0.6, "width": 0.3 },

    "!stageLeft":   { "panning": -0.7 },
    "!stageCenter": { "panning": 0.0 },
    "!stageRight":  { "panning": 0.7 },

    "!largo":       { "tempoPreset": "largo" },
    "!lento":       { "tempoPreset": "lento" },
    "!adagio":      { "tempoPreset": "adagio" },
    "!andante":     { "tempoPreset": "andante" },
    "!moderato":    { "tempoPreset": "moderato" },
    "!allegro":     { "tempoPreset": "allegro" },
    "!vivace":      { "tempoPreset": "vivace" },
    "!presto":      { "tempoPreset": "presto" },
    "!prestissimo": { "tempoPreset": "prestissimo" },

    "!rit":       { "tempoEnvelope": "ritardando" },
    "!acc":       { "tempoEnvelope": "accelerando" },
    "!rubato":    { "tempoEnvelope": "rubato" },
    "!straight":  { "swing": 0.0 },
    "!swing":     { "swing": 0.5 },
    "!shuffle":   { "swing": 0.66 },

    "!jazz":      { "swing": 0.6, "humanization": 0.2, "durScale": 0.9 },
    "!latin":     { "swing": 0.0, "humanization": 0.1, "durScale": 1.0 },
    "!rock":      { "swing": 0.0, "humanization": 0.15, "durScale": 0.95 },
    "!classical": { "swing": 0.0, "humanization": 0.05, "durScale": 1.1 },
    "!swingFeel": { "swing": 0.5, "humanization": 0.15 },

    "!DC":          { "formJump": "DaCapo" },
    "!DS":          { "formJump": "DalSegno" },
    "!Segno":       { "formMark": "Segno" },
    "!Coda":        { "formMark": "Coda" },
    "!ToCoda":      { "formJump": "ToCoda" },
    "!Fine":        { "formEnd": True },
    "!DC_al_Fine":  { "formJump": "DC_al_Fine" },
    "!DS_al_Coda":  { "formJump": "DS_al_Coda" },
    "!repeatStart": { "repeatStart": True },
    "!repeatEnd":   { "repeatEnd": True },

    "!key:C":   { "keyPreset": "C" },
    "!key:G":   { "keyPreset": "G" },
    "!key:D":   { "keyPreset": "D" },
    "!key:A":   { "keyPreset": "A" },
    "!key:E":   { "keyPreset": "E" },
    "!key:B":   { "keyPreset": "B" },
    "!key:F#":  { "keyPreset": "F#" },
    "!key:F":   { "keyPreset": "F" },
    "!key:Bb":  { "keyPreset": "Bb" },
    "!key:Eb":  { "keyPreset": "Eb" },
    "!key:Ab":  { "keyPreset": "Ab" },
    "!key:Db":  { "keyPreset": "Db" },
    "!key:Gb":  { "keyPreset": "Gb" },

    "!pedOn":     { "pedal": "on" },
    "!pedOff":    { "pedal": "off" },
    "!unaCorda":  { "pedal": "unaCorda" },
    "!treCorde":  { "pedal": "treCorde" },
    "!sostPed":   { "pedal": "sostenuto" },

    "!commonTime": { "meter": "4/4" },
    "!cutTime":    { "meter": "2/2" }
  },

  "slurs": {
    "!(": { "slurStart": True },
    "!)": { "slurEnd": True }
  }
}
