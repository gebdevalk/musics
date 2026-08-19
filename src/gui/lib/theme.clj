(ns gui.lib.theme
  "Dark/light JavaFX theming for the whole GUI, wired via
  (musics/gui) -> (gui.lib.core/launch! theme). One stylesheet per
  theme, applied to every Scene (see gui.lib.core) -- built on the
  standard JavaFX/Modena trick of overriding a handful of root color
  variables (-fx-base/-fx-background/-fx-control-inner-background/
  -fx-text-base-color/-fx-accent) rather than restyling every control
  by hand: Modena (JavaFX's own default stylesheet, still in effect
  underneath -- this only overrides its color variables, not the
  whole thing) derives most controls' actual colors from those, so
  overriding them once at .root level recolors buttons/sliders/combo-
  boxes/labels/etc. for free, consistently, across all three window
  kinds.
  Stylesheets are data: URIs (base64-encoded CSS) rather than files on
  disk, so there's nothing to package/ship separately -- JavaFX's own
  CSS loader accepts data: URIs directly in Scene/getStylesheets."
  (:import (java.util Base64)))

(defn- css->data-uri
  [css]
  (str "data:text/css;base64,"
       (.encodeToString (Base64/getEncoder) (.getBytes ^String css "UTF-8"))))

(def ^:private dark-css
  ".root {
     -fx-base: #2b2b2b;
     -fx-background: #2b2b2b;
     -fx-control-inner-background: #3c3f41;
     -fx-control-inner-background-alt: #3c3f41;
     -fx-text-base-color: #e0e0e0;
     -fx-accent: #3574f0;
   }
   .label { -fx-text-fill: #e0e0e0; }
   .titled-panel-title { -fx-text-fill: #e0e0e0; }")

(def ^:private light-css
  ".root {
     -fx-base: #ececec;
     -fx-background: #f4f4f4;
     -fx-control-inner-background: #ffffff;
     -fx-control-inner-background-alt: #ffffff;
     -fx-text-base-color: #202020;
     -fx-accent: #2f6fed;
   }
   .label { -fx-text-fill: #202020; }")

(def ^:private stylesheets
  {:dark (css->data-uri dark-css)
   :light (css->data-uri light-css)})

(defn stylesheet
  "The data: URI stylesheet for theme (:dark or :light) -- unknown/nil
   falls back to :dark, since that's this GUI's own default (see
   gui.lib.state/*state)."
  [theme]
  (get stylesheets theme (:dark stylesheets)))
