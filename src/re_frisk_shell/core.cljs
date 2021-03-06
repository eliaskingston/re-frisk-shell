(ns re-frisk-shell.core
  (:require [reagent.core :as reagent]
            [goog.events :as goog-events]
            [re-frisk-shell.frisk :as frisk]
            [cljs.tools.reader :refer [read-string]]
            [cljs.js :refer [empty-state eval js-eval]]
            [clojure.string :as str])
  (:require-macros [reagent.ratom :refer [reaction]])
  (:import [goog.events EventType]))

(def frisk-style
  {:background-color "#FAFAFA"
   :fontFamily "Consolas,Monaco,Courier New,monospace"
   :fontSize "12px"
   :height "100%"
   :overflow "auto"
   :width "100%"})

(def re-frisk-button-style
  {:fontFamily "Consolas,Monaco,Courier New,monospace"
   :fontSize "12px"
   :display "inline-block"
   :background-color "#CCCCCC"
   :cursor "move"
   :padding "6px"
   :text-align "left"
   :border-radius "2px"
   :border-bottom-left-radius "0px"
   :border-bottom-right-radius "0px"
   :padding-left "2rem"})

(def arrow-style
  {:margin-left "5px"
   :display "inline-block"
   :padding "3px"
   :width "15px"
   :text-align "center"
   :background-color "#CCCCCC"
   :cursor "pointer"
   :border-radius "2px"
   :border-bottom-left-radius "0px"
   :border-bottom-right-radius "0px"
   :padding-left "2rem"})

;reagent d'n'd - https://github.com/borkdude/draggable-button-in-reagent

(defonce draggable (reagent/atom {}))

(defonce ua js/window.navigator.userAgent)
(defonce ie? (or (re-find #"MSIE " ua) (re-find #"Trident/" ua) (re-find #"Edge/" ua)))

(defn get-client-rect [evt]
  (let [r (.getBoundingClientRect (.-target evt))]
    {:left (.-left r), :top (.-top r)}))

(defn mouse-move-handler [offset]
  (fn [evt]
    (let [x (- (.-clientX evt) (:x offset))
          y (- (.-clientY evt) (:y offset))]
      (reset! draggable {:x x :y y}))))

(defn mouse-up-handler [on-move]
  (fn me [evt]
    (goog-events/unlisten js/window EventType.MOUSEMOVE on-move)))

(defn mouse-down-handler [e]
  (let [{:keys [left top]} (get-client-rect e)
        offset             {:x (- (.-clientX e) left)
                            :y (- (.-clientY e) top)}
        on-move            (mouse-move-handler offset)]
    (goog-events/listen js/window EventType.MOUSEMOVE on-move)
    (goog-events/listen js/window EventType.MOUSEUP (mouse-up-handler on-move))))

(defn visibility-button
  [visible? update-fn]
  [:button {:style {:border 0
                    :backgroundColor "transparent" :width "20px" :height "10px"}
            :onClick update-fn}
   [:svg {:viewBox "0 0 100 100"
          :width "100%" :height "100%"
          :style {:transition "all 0.2s ease"
                  :transform (when visible? "rotate(90deg)")}}
    [:polygon {:points "0,0 0,100 100,50" :stroke "black"}]]])

(defn re-frisk-panel [& data]
  (let [expand-by-default (reduce #(assoc-in %1 [:data-frisk %2 :expanded-paths] #{[]}) {} (range (count data)))
        state-atom (reagent/atom expand-by-default)]
    (fn [& data]
      [:div
       (map-indexed (fn [id x]
                      ^{:key id} [frisk/Root x id state-atom]) [data])])))

(defn re-frisk-shell [data deb-data {:keys [on-click x y width height]}]
    (let [style (merge frisk-style {:resize "both" :width "300px" :height "200px"})
          height (if (and ie? (not height)) 200 height)
          style (merge style (when height {:height height :max-height height :overflow "auto"}))
          style (merge style (when width {:width width :max-width width :overflow "auto"}))]
      (when x (swap! draggable assoc :x x))
      (when y (swap! draggable assoc :y y))
      (fn [data deb-data]
        (when (:deb-win-closed? @deb-data)
          [:div {:style (merge {:position "fixed"
                                :left (str (:x @draggable) "px")
                                :top (str (:y @draggable) "px")
                                :z-index 999}
                               (when (or ie? (not (:x @draggable)))
                                 {:bottom "0px"
                                  :right  "20px"}))}
           [:div {:style re-frisk-button-style
                  :on-mouse-down mouse-down-handler}
            [visibility-button (:visible? (:data-frisk @deb-data)) (fn [_] (swap! deb-data assoc-in [:data-frisk :visible?] (not (:visible? (:data-frisk @deb-data)))))]
            "re-frisk"]
           [:div {:style arrow-style
                  :on-click on-click}
            "\u2197"]
           (when (:visible? (:data-frisk @deb-data))
             [:div {:style style}
              [re-frisk-panel @data]])]))))

(defn debugger-messages [re-frame-events deb-data]
    (reagent/create-class
      {:display-name "debugger-messages"
       :component-did-update
                     (fn [this]
                       (let [n (reagent/dom-node this)]
                         (when (:scroll-bottom? @deb-data)
                           (set! (.-scrollTop n) (.-scrollHeight n)))))
       :reagent-render
                     (fn []
                       (let [clrs (:evnt-colors @deb-data)]
                         [:div.debugger-sidebar-messages
                          {:on-scroll #(let [t (.-target %)]
                                         (swap! deb-data assoc
                                                :scroll-bottom?
                                                (= (.-scrollTop t) (- (.-scrollHeight t) (.-offsetHeight t)))))}
                          (map-indexed (fn [id item]
                                         (let [event (first (if (:event item) (:event item) item))
                                               fx? (boolean (re-find #"-fx" (str event)))
                                               db? (boolean (re-find #"-db" (str event)))
                                               clr (event clrs)]
                                           ^{:key id} [:div.messages-entry {:on-click #(swap! deb-data assoc :event-data item)}
                                                       [:span {:style {:display "inline-block"
                                                                       :background-color (cond clr clr fx? "#FF0000" db? "#00FF00" :else "#3d3d3d")
                                                                       :opacity 0.5
                                                                       :width "15px"
                                                                       :height "15px"
                                                                       :overflow "hidden"
                                                                       :padding-bottom "4px"}}
                                                        (cond fx? "fx" db? "db" :else "  ")]
                                                       [:span.messages-entry-content (str event)]])) @re-frame-events)]))}))

(defn event-bar [deb-data]
  (let [evnt-key (reaction (first (or (:event (:event-data @deb-data)) (:event-data @deb-data))))
        clr (reaction (if @evnt-key (@evnt-key (:evnt-colors @deb-data)) ""))]
    (fn []
      [:div {:style {:width "100%" :height "20px" :background-color "#3d3d3d" :color "#ffffff" :position "relative"}}
       [:div "Event"]
       [:input {:style {:position "absolute" :left "50px" :top "0px" :width "60px"}
                :placeholder "#000000" :type "text" :value @clr :max-length "7"
                :on-change #(swap! deb-data assoc-in [:evnt-colors @evnt-key] (-> % .-target .-value))}]
       [:div {:style {:position "absolute" :right "0px" :top "0px" :width "20px" :cursor "pointer"}
              :on-click #(swap! deb-data assoc :event-data nil)} "X"]])))

(defn eval-str [s]
  (eval (empty-state)
        (read-string s)
        {:eval       js-eval
         :source-map true
         :context    :expr}
        (fn [result] result)))

(defn filter-event [text]
  (fn [item]
    (let [name (str/lower-case (name (first (if (:event item) (:event item) item))))
          text (str/lower-case text)]
      (not= (str/index-of name text) nil))))

(defn debugger-shell [re-frame-data re-frame-events deb-data & [imp-hndl exp-hndl]]
  (let [expand-by-default (reduce #(assoc-in %1 [:data-frisk %2 :expanded-paths] #{[]}) {} (range 1))
        expand-by-default2 (reduce #(assoc-in %1 [:data-frisk %2 :expanded-paths] #{[]}) {} (range 1))
        state-atom (reagent/atom expand-by-default)
        state-atom2 (reagent/atom expand-by-default2)
        input-text (reagent/atom "")
        filtered-events (reaction (if (= @input-text "")
                                    @re-frame-events
                                    (filter (filter-event @input-text) @re-frame-events)))
        cljs-text (reagent/atom "")
        input-cljs-text (reagent/atom "")
        _ (swap! re-frame-data assoc :filter (reaction (if (= @cljs-text "")
                                                         "empty"
                                                         ((:value (eval-str @cljs-text))
                                                          @(:app-db @re-frame-data))))
                                     :app-db-sorted (reaction (let [db @(:app-db @re-frame-data)]
                                                                (if (map? db) (into (sorted-map) db) db))))]
    (fn []
      [:div#debugger
       [:div.debugger-sidebar
         [:input {:placeholder "events filter"
                  :style {:width "100%"}
                  :on-change #(reset! input-text (-> % .-target .-value))}]
        [debugger-messages filtered-events deb-data]
        [:div.debugger-sidebar-controls
         [:div.debugger-sidebar-controls-import-export
          (when imp-hndl
            [:span
             [:span {:style {:cursor :pointer}
                     :on-click imp-hndl}
              "Import"]
             " / "
             [:span {:style {:cursor :pointer}
                     :on-click exp-hndl} "Export"]
             " / "])
          [:span {:style {:cursor :pointer}
                  :on-click #(reset! re-frame-events [])} "Clear"]]]]
       [:div#values
        [:div {:style {:display :flex
                       :background-color "#fafafa"}}
         [:textarea {:style {:width "400px" :height "40px"}
                     :placeholder ":value or (fn [app-db] {:value (:value app-db)}) or #(hash-map :value (:value %))"
                     :on-change #(reset! input-cljs-text (-> % .-target .-value))}]
         [:div {:style {:cursor :pointer :padding-left "5px" :padding-right "5px" :margin-left "5px"
                        :background-color "#3d3d3d" :color :white}
                :on-click #(reset! cljs-text @input-cljs-text)} "run"]]
        [:div {:style (merge frisk-style {:height (if (:event-data @deb-data) "calc(100% - 296px)" "calc(100% - 46px)")})}
         [:div
          (map-indexed (fn [id x]
                         ^{:key id} [frisk/Root x id state-atom]) [@re-frame-data])]]
        [:div {:style (merge frisk-style {:height "250" :overflow "hidden" :display (if (:event-data @deb-data) "block" "none")})}
         [event-bar deb-data]
         [:div {:style {:overflow "auto" :height "100%"}}
          (map-indexed (fn [id x]
                         ^{:key id} [frisk/Root x id state-atom2]) [(:event-data @deb-data)])
          [:div {:style {:height "20px"}}]]]]])))

(defn reagent-debugger-shell [re-frame-data]
  (let [expand-by-default (reduce #(assoc-in %1 [:data-frisk %2 :expanded-paths] #{[]}) {} (range 1))
        state-atom (reagent/atom expand-by-default)]
    (fn []
      [:div {:style frisk-style}
       [:div
        (map-indexed (fn [id x]
                       ^{:key id} [frisk/Root x id state-atom]) [@re-frame-data])]])))

(def debugger-page
  "<!DOCTYPE html>
  <html>\n
    <head>\n
      <title>re-frisk debugger</title>
      <meta charset=\"UTF-8\">\n
      <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n
    </head>\n
    <body style=\"margin:0px;padding:0px\">\n
      <script>var saveAs=saveAs||function(e){\"use strict\";if(typeof e===\"undefined\"||typeof navigator!==\"undefined\"&&/MSIE [1-9]\\./.test(navigator.userAgent)){return}var t=e.document,n=function(){return e.URL||e.webkitURL||e},r=t.createElementNS(\"http://www.w3.org/1999/xhtml\",\"a\"),o=\"download\"in r,a=function(e){var t=new MouseEvent(\"click\");e.dispatchEvent(t)},i=/constructor/i.test(e.HTMLElement)||e.safari,f=/CriOS\\/[\\d]+/.test(navigator.userAgent),u=function(t){(e.setImmediate||e.setTimeout)(function(){throw t},0)},s=\"application/octet-stream\",d=1e3*40,c=function(e){var t=function(){if(typeof e===\"string\"){n().revokeObjectURL(e)}else{e.remove()}};setTimeout(t,d)},l=function(e,t,n){t=[].concat(t);var r=t.length;while(r--){var o=e[\"on\"+t[r]];if(typeof o===\"function\"){try{o.call(e,n||e)}catch(a){u(a)}}}},p=function(e){if(/^\\s*(?:text\\/\\S*|application\\/xml|\\S*\\/\\S*\\+xml)\\s*;.*charset\\s*=\\s*utf-8/i.test(e.type)){return new Blob([String.fromCharCode(65279),e],{type:e.type})}return e},v=function(t,u,d){if(!d){t=p(t)}var v=this,w=t.type,m=w===s,y,h=function(){l(v,\"writestart progress write writeend\".split(\" \"))},S=function(){if((f||m&&i)&&e.FileReader){var r=new FileReader;r.onloadend=function(){var t=f?r.result:r.result.replace(/^data:[^;]*;/,\"data:attachment/file;\");var n=e.open(t,\"_blank\");if(!n)e.location.href=t;t=undefined;v.readyState=v.DONE;h()};r.readAsDataURL(t);v.readyState=v.INIT;return}if(!y){y=n().createObjectURL(t)}if(m){e.location.href=y}else{var o=e.open(y,\"_blank\");if(!o){e.location.href=y}}v.readyState=v.DONE;h();c(y)};v.readyState=v.INIT;if(o){y=n().createObjectURL(t);setTimeout(function(){r.href=y;r.download=u;a(r);h();c(y);v.readyState=v.DONE});return}S()},w=v.prototype,m=function(e,t,n){return new v(e,t||e.name||\"download\",n)};if(typeof navigator!==\"undefined\"&&navigator.msSaveOrOpenBlob){return function(e,t,n){t=t||e.name||\"download\";if(!n){e=p(e)}return navigator.msSaveOrOpenBlob(e,t)}}w.abort=function(){};w.readyState=w.INIT=0;w.WRITING=1;w.DONE=2;w.error=w.onwritestart=w.onprogress=w.onwrite=w.onabort=w.onerror=w.onwriteend=null;return m}(typeof self!==\"undefined\"&&self||typeof window!==\"undefined\"&&window||this.content);if(typeof module!==\"undefined\"&&module.exports){module.exports.saveAs=saveAs}else if(typeof define!==\"undefined\"&&define!==null&&define.amd!==null){define(\"FileSaver.js\",function(){return saveAs})}</script>
      <style>\n\nhtml {\n    overflow: hidden;\n    height: 100%;\n}\n\nbody {\n    height: 100%;\n    overflow: auto;\n}\n\n#debugger {\n  width: 100%;\n  height: 100%;\n  font-family: monospace;\n}\n\n#values {\n  display: block;\n  height: 100%;\n  margin: 0;\n  overflow: auto;\n  cursor: default;\n}\n\n.debugger-sidebar {\n  overflow:hidden;\n  resize:horizontal;\n  display: block;\n  float: left;\n  width: 30ch;\n  height: 100%;\n  color: white;\n  background-color: rgb(61, 61, 61);\n}\n\n.debugger-sidebar-controls {\n  width: 100%;\n  text-align: center;\n  background-color: rgb(50, 50, 50);\n}\n\n.debugger-sidebar-controls-import-export {\n  width: 100%;\n  height: 24px;\n  line-height: 24px;\n  font-size: 12px;\n}\n\n.debugger-sidebar-controls-resume {\n  width: 100%;\n  height: 30px;\n  line-height: 30px;\n  cursor: pointer;\n}\n\n.debugger-sidebar-controls-resume:hover {\n  background-color: rgb(41, 41, 41);\n}\n\n.debugger-sidebar-messages {\n  width: 100%;\n  overflow-y: auto;\n  height: calc(100% - 44px);\n}\n\n.debugger-sidebar-messages-paused {\n  width: 100%;\n  overflow-y: auto;\n  height: calc(100% - 54px);\n}\n\n.messages-entry {\n  cursor: pointer;\n  width: 100%;\n}\n\n.messages-entry:hover {\n  background-color: rgb(41, 41, 41);\n}\n\n.messages-entry-selected, .messages-entry-selected:hover {\n  background-color: rgb(10, 10, 10);\n}\n\n.messages-entry-content {\n  width: 23ch;\n  padding-top: 4px;\n  padding-bottom: 4px;\n  padding-left: 1ch;\n  text-overflow: ellipsis;\n  white-space: nowrap;\n  overflow: hidden;\n  display: inline-block;\n}\n\n.messages-entry-index {\n  color: #666;\n  width: 5ch;\n  padding-top: 4px;\n  padding-bottom: 4px;\n  padding-right: 1ch;\n  text-align: right;\n  display: block;\n  float: right;\n}\n\n</style>
      <div id=\"app\" style=\"height:100%;width:100%\">\n
        <h2>re-frisk debugger</h2>\n
        <p>ENJOY!</p>\n
      </div>\n
    </body>\n
  </html>")