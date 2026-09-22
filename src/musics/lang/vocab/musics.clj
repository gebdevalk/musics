(ns musics.lang.vocab.musics
  "musics.lang's own `musics` vocabulary -- everything else that
   exists only because this kernel also hosts musics text (repo
   navigation/inspection, MIDI/playback, generative transforms,
   variables, persistence, the action registry/scheduler, and a
   handful of misc/state words) once parse/parse-notation/s!/
   try-parse/parse-file/>ids (now `musics.lang.vocab.parse`) and
   core.wall's own algorithm-registry bridge (now
   `musics.lang.vocab.algorithms`) were split into their own sibling
   vocabularies -- see musics.lang's own ns docstring for why. Mechanical
   translation of input.forth's own musics-prims, same argument-
   marshaling conventions (->kw/callable->fn), just lowercased."
  (:require [musics.core :as m]
            [musics.lang.runtime :refer [push! pop-val! builtin ->kw callable->fn]]))

(defn vocab []
  (merge
    ;; -- registry / navigation / inspection -----------------------------
    (builtin "find" (fn [ctx] (push! ctx (m/find (->kw (pop-val! ctx))))) "( id -- node/f )" "looks up a node by id in the repo")
    (builtin "ids" (fn [ctx] (push! ctx (m/ids))) "( -- ids )" "every id currently in the repo")
    (builtin "root-children" (fn [ctx] (push! ctx (m/root-children))) "( -- ids )" "the top-level parts directly under :ROOT")
    (builtin "children" (fn [ctx] (push! ctx (m/children (->kw (pop-val! ctx))))) "( id -- children )" "a container's own immediate children")
    (builtin "leaves" (fn [ctx] (push! ctx (m/leaves (->kw (pop-val! ctx))))) "( id -- leaves )" "every leaf note/rest under an id, in order")
    (builtin "sq" (fn [ctx] (push! ctx (m/sq (->kw (pop-val! ctx))))) "( id -- seq )" "a container's own children as a bare, playable seq")
    (builtin "inspect" (fn [ctx] (m/inspect (->kw (pop-val! ctx)))) "( id -- )" "prints a node's own structure")
    (builtin "inspect-all" (fn [_ctx] (m/inspect)) "( -- )" "prints a session-wide node-count overview")
    (builtin "ctx" (fn [ctx] (m/ctx (->kw (pop-val! ctx)))) "( id -- )" "prints a part's own context chain")
    (builtin "ctx-value" (fn [ctx] (let [time (pop-val! ctx) key (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (push! ctx (m/ctx-value id key time))))
             "( id key time -- value )" "samples one context key's own value at a given time")
    (builtin "locate" (fn [ctx] (let [path (pop-val! ctx) id (->kw (pop-val! ctx))]
                                    (push! ctx (m/locate id path))))
             "( id path -- node )" "navigates from id along an explicit selector path")
    (builtin "describe" (fn [ctx] (push! ctx (m/describe (->kw (pop-val! ctx))))) "( id -- str )" "a human-readable description of a node")
    (builtin "print-structure" (fn [ctx] (m/print-structure (->kw (pop-val! ctx)))) "( id -- )" "prints a node's own full tree structure")
    (builtin "expand" (fn [ctx] (push! ctx (m/expand (pop-val! ctx)))) "( leaf -- path )" "finds where a real leaf value sits in the repo tree")

    ;; -- MIDI / playback -------------------------------------------------
    (builtin "connect" (fn [_ctx] (m/connect)) "( -- )" "opens the Fluidsynth MIDI connection")
    (builtin "warm-up!" (fn [_ctx] (m/warm-up!)) "( -- )" "sends a silent note to wake the synth up")
    (builtin "warm-up-n!" (fn [ctx] (let [ms (pop-val! ctx) n (pop-val! ctx)] (m/warm-up! n ms))) "( n ms -- )" "warm-up!, n times, ms apart")
    (builtin "disconnect" (fn [_ctx] (m/disconnect)) "( -- )" "closes the MIDI connection")
    (builtin "play" (fn [ctx] (m/play (->kw (pop-val! ctx)))) "( id -- )" "flushes every voice, then plays id")
    (builtin "play-add" (fn [ctx] (push! ctx (m/play-add (->kw (pop-val! ctx))))) "( id -- path )" "plays id alongside whatever's already playing")
    (builtin "play-change" (fn [ctx] (let [arg (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                         (push! ctx (m/play-change path arg))))
             "( path id -- path )" "replaces one already-playing track's own material")
    (builtin "voice-at" (fn [ctx] (push! ctx (m/voice-at (->kw (pop-val! ctx))))) "( path -- voice )" "the live voice map at a given track path")
    (builtin "play-file!" (fn [ctx] (m/play-file! (pop-val! ctx))) "( path -- )" "parses, commits, and plays a .mus file in one step")
    (builtin "display" (fn [ctx] (push! ctx (m/display (->kw (pop-val! ctx))))) "( id -- )" "a synchronous, silent preview of what play would do")
    (builtin "stop!" (fn [_ctx] (m/stop!)) "( -- )" "stops every voice, sending note-off promptly")
    (builtin "pause!" (fn [_ctx] (m/pause!)) "( -- )" "freezes playback in place, no retrigger on resume")
    (builtin "resume!" (fn [_ctx] (m/resume!)) "( -- )" "resumes playback after pause!")
    (builtin "all-notes-off" (fn [_ctx] (m/all-notes-off)) "( -- )" "sends an immediate all-notes-off panic message")
    (builtin "play!" (fn [ctx] (let [v (pop-val! ctx)
                                       {:keys [ids]} (if (string? v) (m/parse v) v)]
                                   (m/play (vec ids))))
             "( text/{:ids ids} -- )" "parses (if needed), commits, and plays in one step")
    (builtin "p!" (fn [ctx] (m/p! (pop-val! ctx))) "( text -- )" "musics.core/play!'s own short name")

    ;; -- generative transforms -------------------------------------------
    (builtin "times" (fn [ctx] (let [material (pop-val! ctx) n (pop-val! ctx)]
                                   (push! ctx (m/times n material))))
             "( n material -- material' )" "repeats material n times")
    (builtin "transpose" (fn [ctx] (let [material (pop-val! ctx) semitones (pop-val! ctx)]
                                       (push! ctx (m/transpose semitones material))))
             "( semitones material -- material' )" "shifts every pitch by a fixed number of semitones")
    (builtin "invert" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx)]
                                    (push! ctx (m/invert axis material))))
             "( axis material -- material' )" "mirrors every pitch around an axis")
    (builtin "invert-mean" (fn [ctx] (push! ctx (m/invert (pop-val! ctx)))) "( material -- material' )" "invert, axis defaulted to the material's own mean pitch")
    (builtin "scale" (fn [ctx] (let [material (pop-val! ctx) factor (pop-val! ctx)]
                                   (push! ctx (m/scale factor material))))
             "( factor material -- material' )" "scales every duration by a fixed factor")
    (builtin "reverse" (fn [ctx] (push! ctx (m/reverse (pop-val! ctx)))) "( material -- material' )" "reverses material's own order")
    (builtin "shuffle" (fn [ctx] (push! ctx (m/shuffle (pop-val! ctx)))) "( material -- material' )" "randomly reorders material")
    (builtin "thread" (fn [ctx] (let [material (pop-val! ctx) f (callable->fn ctx (pop-val! ctx))]
                                    (push! ctx (m/thread f material))))
             "( f material -- material' )" "applies f to every element of material")
    (builtin "active-key" (fn [ctx] (push! ctx (m/active-key (->kw (pop-val! ctx))))) "( id -- ks )" "the key currently in scope for a part")
    (builtin "tonal-transpose" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx) ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-transpose ks steps material))))
             "( ks steps material -- material' )" "transposes by scale steps within a key, not raw semitones")
    (builtin "tonal-invert" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx) ks (pop-val! ctx)]
                                          (push! ctx (m/tonal-invert ks axis material))))
             "( ks axis material -- material' )" "invert, staying diatonic to a key")
    (builtin "snap-to-scale" (fn [ctx] (let [material (pop-val! ctx) ks (pop-val! ctx)]
                                           (push! ctx (m/snap-to-scale ks material))))
             "( ks material -- material' )" "moves every pitch to the nearest note in a key's own scale")
    (builtin "tonal-harmonize" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx) ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-harmonize ks steps material))))
             "( ks steps material -- material' )" "adds a diatonic harmony voice, steps above")

    ;; -- variables --------------------------------------------------------
    (builtin "clear-vars" (fn [_ctx] (m/clear-vars)) "( -- )" "clears every \\name-referenceable variable")

    ;; -- persistence --------------------------------------------------------
    (builtin "write" (fn [ctx] (m/write (pop-val! ctx))) "( path -- )" "saves the whole current repo to a file")
    (builtin "load" (fn [ctx] (m/load (pop-val! ctx))) "( path -- )" "replaces the current repo with a saved file's own content")
    (builtin "ly-to-mus" (fn [ctx] (push! ctx (m/ly-to-mus (pop-val! ctx)))) "( path -- mus-path )" "converts a LilyPond file to a sibling .mus file")

    ;; -- reset / help -------------------------------------------------------
    (builtin "reset" (fn [_ctx] (m/reset)) "( -- )" "wipes the repo entirely, re-bootstraps a fresh :ROOT")
    (builtin "help" (fn [_ctx] (m/help)) "( -- )" "prints musics.core's own full context-key help table")
    (builtin "help?" (fn [ctx] (m/help (pop-val! ctx))) "( key -- )" "prints musics.core's own help for one context key")

    ;; -- action registry / schedule -------------------------------------------
    (builtin "register-action!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                              (m/register-action! id f)))
             "( id f -- )" "parks a callable action under id, for trigger!/schedule!")
    (builtin "unregister-action!" (fn [ctx] (m/unregister-action! (->kw (pop-val! ctx)))) "( id -- )" "removes a registered action")
    (builtin "trigger!" (fn [ctx] (let [arg (pop-val! ctx) id (->kw (pop-val! ctx))]
                                      (push! ctx (m/trigger! id arg))))
             "( id arg -- result )" "runs a registered action directly, right now")
    (builtin "schedule!" (fn [ctx] (let [action-id (->kw (pop-val! ctx)) phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (m/schedule! id phase action-id)))
             "( id phase action-id -- )" "arms a one-shot action for the next [id phase] boundary")
    (builtin "unschedule!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                         (m/unschedule! id phase)))
             "( id phase -- )" "cancels a scheduled one-shot action")
    (builtin "scheduled" (fn [ctx] (push! ctx (m/scheduled))) "( -- )" "prints every pending one-shot schedule entry")
    (builtin "scheduled?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                        (push! ctx (m/scheduled id phase))))
             "( id phase -- entry/f )" "checks one specific one-shot schedule slot")
    (builtin "scheduled-repeating" (fn [ctx] (push! ctx (m/scheduled-repeating))) "( -- )" "prints every pending repeating (schedule-tx!) entry")
    (builtin "scheduled-repeating?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                  (push! ctx (m/scheduled-repeating id phase))))
             "( id phase -- entry/f )" "checks one specific repeating schedule slot")
    (builtin "unschedule-repeating!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                   (m/unschedule-repeating! id phase)))
             "( id phase -- )" "cancels a repeating schedule-tx! entry")
    (builtin "schedule-tx!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                          (push! ctx (m/schedule-tx! id phase))))
             "( id phase -- )" "redirects one voice's own :view to whatever's newly committed, next boundary")

    ;; -- misc / state ---------------------------------------------------
    (builtin "music-eval" (fn [ctx] (push! ctx (m/music-eval (pop-val! ctx)))) "( text -- result )" "mu!'s own :eval hook, callable directly")
    (builtin "session" (fn [ctx] (push! ctx @m/session)) "( -- session )" "the current {:auto-ids :var-map} session map")
    (builtin "receiver" (fn [ctx] (push! ctx @m/receiver)) "( -- receiver/nil )" "the current MIDI output receiver, if connected")))
