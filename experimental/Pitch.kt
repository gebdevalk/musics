package musics.elements

import musics.parameters.Key
import java.util.*

/*
Scientific 	C-1 	    C0 	        C1 	    C2 	    C3 	   C4 	    C5 	    C6 	    C7 	    C8 	    C9
Helmholtz 	C,,, 	    C,, 	    C, 	    C 	    c 	    c' 	    c'' 	c''' 	c'''' 	c''''' 	c''''''
Name 	    Dbl Contra 	Sub Contra 	Contra 	Great 	Small 	1 Line 	2 Line 	3 Line 	4 Line 	5 Line 	6 Line
MIDI 	   -5 	        -4 	        -3 	    -2 	    -1 	    0 	    1 	    2 	    3 	    4 	    5
MIDI Note 	0 	        12 	        24 	    36 	    48 	    60 	    72 	    84 	    96 	    108 	120
*/

typealias Pitch = Int

var previous: Int = 0

fun asNoteName(number: Int, signature: Signature): String {
    val index = number % 12
    val names = if (number >= previous) signature.rising else signature.falling
    previous = number
    return names[index].lowercase(Locale.getDefault())
}

fun asNoteName(number: Int, key: Key): String = asNoteName(number, key.signature)

fun asNoteName(number: Int) = asNoteName(number, Signature.C)

fun asNoteNameWithOctave(number: Int, signature: Signature): String {
//    if (number == 0) return "r"
    val octave = number / 12 - 1
    val name = asNoteName(number, signature)
    return name + octave
}

fun asNoteNameWithOctave(number: Int, key: Key): String = asNoteNameWithOctave(number, key.signature)

fun asNoteNameWithOctave(number: Int) = asNoteNameWithOctave(number, Signature.C)


const val Bis = 0
const val C = 0
const val Cis = 1
const val Des = 1
const val D = 2
const val Dis = 3
const val Es = 3
const val E = 4
const val Fes = 4
const val Eis = 5
const val F = 5
const val Fis = 6
const val Ges = 6
const val G = 7
const val Gis = 8
const val As = 8
const val A = 9
const val Ais = 10
const val Bes = 10
const val B = 11
const val Ces = 11
const val Bis0 = 12
const val C0 = 12
const val Cis0 = 13
const val Des0 = 13
const val D0 = 14
const val Dis0 = 15
const val Es0 = 15
const val E0 = 16
const val Fes0 = 16
const val Eis0 = 17
const val F0 = 17
const val Fis0 = 18
const val Ges0 = 18
const val G0 = 19
const val Gis0 = 20
const val As0 = 20
const val A0 = 21
const val Ais0 = 22
const val Bes0 = 22
const val B0 = 23
const val Ces0 = 23
const val Bis1 = 24
const val C1 = 24
const val Cis1 = 25
const val Des1 = 25
const val D1 = 26
const val Dis1 = 27
const val Es1 = 27
const val E1 = 28
const val Fes1 = 28
const val Eis1 = 29
const val F1 = 29
const val Fis1 = 30
const val Ges1 = 30
const val G1 = 31
const val Gis1 = 32
const val As1 = 32
const val A1 = 33
const val Ais1 = 34
const val Bes1 = 34
const val B1 = 35
const val Ces1 = 35
const val Bis2 = 36
const val C2 = 36
const val Cis2 = 37
const val Des2 = 37
const val D2 = 38
const val Dis2 = 39
const val Es2 = 39
const val E2 = 40
const val Fes2 = 40
const val Eis2 = 41
const val F2 = 41
const val Fis2 = 42
const val Ges2 = 42
const val G2 = 43
const val Gis2 = 44
const val As2 = 44
const val A2 = 45
const val Ais2 = 46
const val Bes2 = 46
const val B2 = 47
const val Ces2 = 47
const val Bis3 = 48
const val C3 = 48
const val Cis3 = 49
const val Des3 = 49
const val D3 = 50
const val Dis3 = 51
const val Es3 = 51
const val E3 = 52
const val Fes3 = 52
const val Eis3 = 53
const val F3 = 53
const val Fis3 = 54
const val Ges3 = 54
const val G3 = 55
const val Gis3 = 56
const val As3 = 56
const val A3 = 57
const val Ais3 = 58
const val Bes3 = 58
const val B3 = 59
const val Ces3 = 59
const val Bis4 = 60
const val C4 = 60
const val Cis4 = 61
const val Des4 = 61
const val D4 = 62
const val Dis4 = 63
const val Es4 = 63
const val E4 = 64
const val Fes4 = 64
const val Eis4 = 65
const val F4 = 65
const val Fisis4 = 66
const val Ges4 = 66
const val G4 = 67
const val Gis4 = 68
const val As4 = 68
const val A4 = 69
const val Ais4 = 70
const val Bes4 = 70
const val B4 = 71
const val Ces4 = 71
const val Bis5 = 72
const val C5 = 72
const val Cis5 = 73
const val Des5 = 73
const val D5 = 74
const val Dis5 = 75
const val Es5 = 75
const val E5 = 76
const val Fes5 = 76
const val Eis5 = 77
const val F5 = 77
const val Fis5 = 78
const val Ges5 = 78
const val G5 = 79
const val Gis5 = 80
const val As5 = 80
const val A5 = 81
const val Ais5 = 82
const val Bes5 = 82
const val B5 = 83
const val Ces5 = 83
const val Bis6 = 84
const val C6 = 84
const val Cis6 = 85
const val Des6 = 85
const val D6 = 86
const val Dis6 = 87
const val Es6 = 87
const val E6 = 88
const val Fes6 = 88
const val Eis6 = 89
const val F6 = 89
const val Fis6 = 90
const val Ges6 = 90
const val G6 = 91
const val Gis6 = 92
const val As6 = 92
const val A6 = 93
const val Ais6 = 94
const val Bes6 = 94
const val B6 = 95
const val Ces6 = 95
const val Bis7 = 96
const val C7 = 96
const val Cis7 = 97
const val Des7 = 97
const val D7 = 98
const val Dis7 = 99
const val Es7 = 99
const val E7 = 100
const val Fes7 = 100
const val Eis7 = 101
const val F7 = 101
const val Fis7 = 102
const val Ges7 = 102
const val G7 = 103
const val Gis7 = 104
const val As7 = 104
const val A7 = 105
const val Ais7 = 106
const val Bes7 = 106
const val B7 = 107
const val Ces7 = 107
const val Bis8 = 108
const val C8 = 108
const val Cis8 = 109
const val Des8 = 109
const val D8 = 110
const val Dis8 = 111
const val Es8 = 111
const val E8 = 112
const val Fes8 = 112
const val Eis8 = 113
const val F8 = 113
const val Fis8 = 114
const val Ges8 = 114
const val G8 = 115
const val Gis8 = 116
const val As8 = 116
const val A8 = 117
const val Ais8 = 118
const val Bes8 = 118
const val B8 = 119
const val Ces8 = 119
const val Bis9 = 120
const val C9 = 120
const val Cis9 = 121
const val Des9 = 121
const val D9 = 122
const val Dis9 = 123
const val Es9 = 123
const val E9 = 124
const val Fes9 = 124
const val Eis9 = 125
const val F9 = 125
const val Fis9 = 126
const val Ges9 = 126
const val G9 = 127
