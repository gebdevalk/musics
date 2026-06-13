from typing import List

import regex

lilypond_text = """
    %{ 
    this is a multiline
    comment
    %}
    % this is a comment line
    
    melody = { c''4 d'' e'' f'' }
    harmony = { <c e g>2 <d f a> }

    \\header {
        title = "Polyphonic Etude"
        composer = "J.S. Bach"
    }

    \\score {
        \\new Staff {
            \\set Staff.midiInstrument = #"piano"
            \\tempo 4 = 100
            \\time 4/4
            \\key c \\major

            <<
                \\new Voice {
                    \\voiceOne
                    \\melody
                    g''1
                }
                \\new Voice {
                    \\voiceTwo
                    c'4 e' g' b'
                    c''1
                }
                \\new Voice {
                    \\voiceThree
                    \\harmony
                    <e g c'>1
                }
            >>

            \\tuplet 3/2 {
                <<
                    { c'4 d' e' }
                    \\\\
                    { c4 a, f, }
                >>
            }
        }
        \\layout { }
        \\midi { }
    }
    """

lilypond_text2 = """
    \\version "2.24.0"

\\header {
  title = "MIDI Instrument Example"
  composer = "LilyPond User"
}

% Define a piano staff with acoustic grand piano
pianoMusic = \\relative c' {
  \\clef treble
  \\key c \\major
  \\time 4/4

  c4 e g c | b g e c |
  d f a d | c2 r2 \\bar "|."
}

pianoDynamics = {
  s1\\mp | s1 | s1\\mf | s1\\f |
}

\\score {
  <<
    \\new Staff = "piano" \\with {
      instrumentName = "Piano"
      midiInstrument = "acoustic grand"
    } {
      \\pianoMusic
    }
  >>
  \\layout { }
  \\midi {
    \\tempo 4 = 120
    \\context {
      \\Staff
      midiInstrument = "acoustic grand"
    }
  }
}

% Multiple instruments example
violinMusic = \\relative c'' {
  \\clef treble
  \\key c \\major
  \\time 4/4
  g4-.\\mp g-. g-. g-. |
  a-. a-. a-. a-. |
  b-.\\mf b-. b-. b-. |
  c2 r2 \\bar "|."
}

celloMusic = \\relative c {
  \\clef bass
  \\key c \\major
  \\time 4/4
  c2\\mp e | g c, |
  f2\\mf a | c r2 \\bar "|."
}

\\score {
  <<
    \\new Staff \\with {
      instrumentName = "Violin"
      midiInstrument = "violin"
    } { \\violinMusic }

    \\new Staff \\with {
      instrumentName = "Cello"
      midiInstrument = "cello"
    } { \\celloMusic }
  >>
  \\layout { }
  \\midi {
    \\tempo 4 = 100
  }
}
"""

block_pattern = r'\{(?:[^{}]|(?R))*\}'
# block_pattern = r'\{[^{}]*(?:(?R)[^{}]*)*\}'
block_pattern_with_outer_braces = r'(\{(?:[^{}]|(?R))*\})'
command_pattern = r'(\\[a-zA-Z_]+)'
command_block_pattern_with_outer_braces = r'(\\[a-zA-Z_]+) (\{(?:[^{}]|(?R))*\})'


lilypond_text = regex.sub(r'%\{.*?%\}', '', lilypond_text, flags=regex.DOTALL)

lilypond_text = regex.sub(r'%.*$', '', lilypond_text, flags=regex.MULTILINE)

lilypond_text = regex.sub(r'\s+', ' ', lilypond_text)
print(lilypond_text)

matches = regex.findall(command_block_pattern_with_outer_braces, lilypond_text)
for match in matches:
    print(match)
    # print(match[1:-1])  # Remove the outer braces

# def tokenize(lilypond_text: str) -> List[str]:
#     """Split Lilypond text into tokens based on keywords"""
#     # Remove comments
#     text = re.sub(r'%.*$', '', lilypond_text, flags=re.MULTILINE)
#     text = re.sub(r'%\{.*?%\}', '', text, flags=re.DOTALL)
#     text = re.sub(r'\s+', ' ', text)
#     print(text)
#
#     # Pattern for Lilypond tokens
#     pattern = r'\\[a-zA-Z]+|[a-gr](?:is+|s|es+|[!\'])?[,!\']*|\d+|[()<>~\[\]{}]|-[\.,\^\+\|>]|r\d*|R\d*|\.+'
#     return re.findall(pattern, text)
#
#
# def is_note_token(token: str) -> bool:
#     """Check if token represents a note"""
#     return bool(re.match(r'^[a-gr](?:is+|s|es+|[!\'])?[,!\']*\d*\.*$', token))

