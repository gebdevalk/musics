(ns gui.lib.data
  "Read-only access to musics' own reference data tables
  (common.music-data) for GUI pickers -- a dropdown here is backed by
  the SAME table the text language itself resolves an instruction
  against (!instrument:, !mf/!f/etc. via common.music-data/dynamics,
  !allegro/!andante/etc. via tempo-markings), never a separate,
  GUI-side copy that could drift from it.

  Each lookup below is {:items :name->value :value->name}:
    :items       -- sorted display-name strings, for a combo-box
    :name->value -- display name -> the real numeric value to write
                    into a Context (a MIDI program number, a velocity,
                    a BPM, ...)
    :value->name -- the reverse, for showing a Context's CURRENT value
                    as a name when a container is first watched."
  (:require [common.music-data :as md]))

(defn- name-lookup
  "Build a {:items :name->value :value->name} lookup from any
   keyword-keyed map whose values are plain numbers (dynamics,
   tempo-markings, ...) or from an already-built {name -> number} map
   (gm-sound-set needs its own :prog extracted first, see instruments
   below)."
  [name->value-map]
  (let [name->value (into (sorted-map)
                           (map (fn [[k v]] [(name k) v]) name->value-map))]
    {:items (vec (keys name->value))
     :name->value name->value
     :value->name (into {} (map (fn [[k v]] [v k]) name->value))}))

(def instruments
  "General MIDI patch names -> gm-sound-set's own :prog number, the
   exact value common.music-data/program->name already expects back
   (see that ns's own reverse-lookup def) -- this is that same table,
   just re-keyed by display name instead of by number."
  (name-lookup (into {} (map (fn [[k v]] [k (:prog v)]) md/gm-sound-set))))

(def dynamics
  "Dynamic mark names (:pp :mf :ff ...) -> the velocity value !mf/!ff/
   etc. already set on :volume when authored as text."
  (name-lookup md/dynamics))

(def tempo-markings
  "Named tempo marks (:andante :allegro ...) -> the BPM !andante/
   !allegro/etc. already set on :Tempo when authored as text."
  (name-lookup md/tempo-markings))

(def articulations
  "Articulation names -> their {:duration :dynamic} record. Not a
   single scalar like the others above, so no combo-specs entry
   writes through this one automatically yet -- exposed for any
   future picker that wants the full record."
  {:items (vec (sort (map name (keys md/articulations))))
   :table md/articulations})

(def scales
  "Scale names -> their {:intervals :display :offset ...} record --
   for a future scale-picker driving musics.clj's tonal-* fns rather
   than a plain Context value, exposed here for the same reason
   articulations is."
  {:items (vec (sort (map name (keys md/scales))))
   :table md/scales})
