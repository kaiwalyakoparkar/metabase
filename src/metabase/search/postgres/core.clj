(ns metabase.search.postgres.core
  (:require
   [honey.sql :as sql]
   [honey.sql.helpers :as sql.helpers]
   [metabase.api.common :as api]
   [metabase.search.config :as search.config]
   [metabase.search.impl :as search.impl]
   [metabase.search.postgres.index :as search.index]
   [metabase.search.postgres.ingestion :as search.ingestion]
   [toucan2.core :as t2]))

(defn- user-params [search-ctx]
  (cond
    (:current-user-id search-ctx)
    (select-keys search-ctx [:is-superuser? :current-user-id :current-user-perms])

    api/*current-user-id*
    {:is-superuser?      api/*is-superuser?*
     :current-user-id    api/*current-user-id*
     :current-user-perms @api/*current-user-permissions-set*}

    :else
    {:is-superuser?      true
     ;; this does not matter, we won't use it.
     :current-user-id    1
     :current-user-perms #{"/"}}))

(defn- in-place-query [{:keys [models search-term archived?] :as search-ctx}]
  (search.impl/full-search-query
   (merge
    (user-params search-ctx)
    {:search-string      search-term
     :models             (or models
                             (if api/*current-user-id*
                               search.config/all-models
                               ;; For REPL convenience, skip these models as
                               ;; they require the user to be initialized.
                               (disj search.config/all-models "indexed-entity")))
     :archived?          archived?
     :model-ancestors?   true})))

(defn hybrid
  "Use the index for appling the search string, but rely on the legacy code path for rendering
  the display data, applying permissions, additional filtering, etc.

  NOTE: this is less efficient than legacy search even. We plan to replace it with something
  less feature complete, but much faster."
  [search-term & {:as search-ctx}]
  (when-not @#'search.index/initialized?
    (throw (ex-info "Search index is not initialized. Use [[init!]] to ensure it exists."
                    {:search-engine :postgres})))
  (-> (sql.helpers/with [:index-query (search.index/search-query search-term)]
                        [:source-query (in-place-query search-ctx)])
      (sql.helpers/select :sq.*)
      (sql.helpers/from [:source-query :sq])
      (sql.helpers/join [:index-query :iq] [:and
                                            [:= :sq.model :iq.model]
                                            [:= :sq.id :iq.model_id]])
      (sql/format {:quoted true})
      t2/reducible-query))

(defn hybrid-multi
  "Perform multiple legacy searches to see if its faster. Perverse!"
  [search-term & {:as search-ctx}]
  (when-not @#'search.index/initialized?
    (throw (ex-info "Search index is not initialized. Use [[init!]] to ensure it exists."
                    {:search-engine :postgres})))
  (->> (search.index/search-query search-term)
       t2/query
       (group-by :model)
       (mapcat (fn [[model results]]
                 (let [ids (map :model_id results)]
                   ;; Something is very wrong here, this also returns items with other ids.
                   (as-> search-ctx <>
                     (assoc <> :models #{model} :ids ids)
                     (dissoc <> :search-string)
                     (in-place-query <>)
                     (t2/query <>)
                     (filter (comp (set ids) :id) <>)))))))

(defn search
  "Return a reducible-query corresponding to searching the entities via a tsvector."
  [search-ctx]
  (hybrid-multi (:search-string search-ctx)
                (dissoc search-ctx :search-string)))

(defn init!
  "Ensure that the search index exists, and has been populated with all the entities."
  [& [force-reset?]]
  (when (or force-reset? (not (#'search.index/exists? @#'search.index/active-table)))
    (search.index/reset-index!))
  (search.ingestion/populate-index!))

(comment
  (init! true))
