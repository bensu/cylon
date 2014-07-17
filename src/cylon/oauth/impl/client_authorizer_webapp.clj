(ns cylon.oauth.impl.client-authorizer-webapp
  (require [com.stuartsierra.component :as component]
           [clojure.tools.logging :refer :all]
           [cylon.oauth.scopes :refer (Scopes) ]
           [modular.bidi :refer (WebService)]
           [bidi.bidi :refer (path-for)]
           [hiccup.core :refer (html h)]
           [schema.core :as s]
           [clojure.string :as str]
           [cylon.oauth.application-registry :refer ( lookup-application+)]
           [cylon.user :refer (verify-user)]
           [cylon.totp :refer (OneTimePasswordStore get-totp-secret totp-token)]
           [clj-time.core :refer (now plus days)]
           [cheshire.core :refer (encode)]
           [clj-jwt.core :refer (to-str sign jwt)]
           [ring.middleware.params :refer (wrap-params)]
           [cylon.session :refer (create-session! assoc-session! ->cookie get-session-value get-cookie-value get-session)]
           [ring.middleware.cookies :refer (wrap-cookies cookies-request cookies-response)]))

(defrecord ClientAuthorizerWebApp [store scopes iss]
  Scopes
  (valid-scope? [_ scope] (contains? scopes scope))

  WebService
  (request-handlers [this]
    {::authorize
     (-> (fn [req]
        ;; TODO Establish whether the user-agent is already authenticated.
        ;; If not, create a session with client-id, scope and state and redirect to the login form
        (if-let [session
                 (get-session
                  (:session-store this)
                  (-> req cookies-request :cookies (get "session-id") :value))]
          ;; TODO Obey the 'prompt' value in OpenID/Connect
          (do
            (infof "Hi - it appears you're already logged in, session is %s" (pr-str session))
            {:status 302
             :headers {"Location" (path-for (:modular.bidi/routes req) ::get-authenticate-form)}})
          (let [session (create-session!
                         (:session-store this)
                         {:client-id (-> req :query-params (get "client_id"))
                          :scope (-> req :query-params (get "scope"))
                          :state (-> req :query-params (get "state"))
                          })]
            (infof "Hi - it appears you're not already logged in, so I'm going to create a session for you and redirect you")
            (cookies-response
                      {:status 302
                       :headers {"Location" (path-for (:modular.bidi/routes req) ::get-authenticate-form)}
                       :cookies {"session-id" (->cookie session)}}))))
         wrap-params)

     ::get-authenticate-form
     (->
      (fn [req]
        {:status 200
         :body (html
                [:body
                 [:h1 "API Server"]
                 [:p "The application with client id " (get-session-value req  "session-id" (:session-store this) :client-id)
                  " is requesting access to the Azondi API on your behalf. Please login if you are happy to authorize this application."]
                 [:form {:method :post
                         :action (path-for (:modular.bidi/routes req) ::post-authenticate-form)}
                  [:p
                   [:label {:for "user"} "User"]
                   [:input {:name "user" :id "user" :type "text"}]]
                  [:p
                   [:label {:for "password"} "Password"]
                   [:input {:name "password" :id "password" :type "password"}]]
                  [:p [:input {:type "submit"}]]
                  [:p [:a {:href (path-for (:modular.bidi/routes req) :cylon.impl.signup/signup-form)} "Signup"]]]])})
      wrap-params)

     ::post-authenticate-form
     (-> (fn [req]
           (let [params (-> req :form-params)
                 identity (get params "user")
                 password (get params "password")
                 client-id (get-session-value req  "session-id" (:session-store this) :client-id)
                 scope (get-session-value req  "session-id" (:session-store this) :scope)
                 state (get-session-value req  "session-id" (:session-store this) :state)
                 scopes (set (str/split scope #"[\s]+"))
                 ;; Lookup application
                 {:keys [callback-uri] :as application}
                 (lookup-application+ (:application-registry this) client-id)]

             ;; openid-connect core 3.1.2.2
             ;;(if (contains? scopes "openid"))

             (if (and identity
                      (not-empty identity)
                      (verify-user (:user-domain this) (.trim identity) password))

               (let [session (create-session! (:session-store this) {:cylon/identity identity})]
                 (if (and ; we want one clause here, so we only have one then and one else clause to code
                      (satisfies? OneTimePasswordStore (:user-domain this))
                      (when-let [secret (get-totp-secret (:user-domain this) identity password)]
                        (assoc-session! (:session-store this)
                                        (:cylon.session/key session)
                                        :totp-secret secret)
                        true ; it does, but just in case assoc-session! semantics change
                        ))

                   ;; So it's 2FA
                   (do
                     ;; Let's remember the client-id and state in the
                     ;; session, we'll need them later
                     (assoc-session! (:session-store this)
                                     (:cylon.session/key session)
                                     :client-id client-id)

                     (assoc-session! (:session-store this)
                                     (:cylon.session/key session)
                                     :state state)

                     (cookies-response {:status 302
                       :headers {"Location" (path-for (:modular.bidi/routes req) ::get-totp-code)}
                       :cookies {"session-id" (->cookie session)}}))

                   ;; So it's not 2FA, continue with OAuth exchange
                   ;; Generate the temporary code that we'll exchange for an access token later
                   (let [code (str (java.util.UUID/randomUUID))]

                     ;; Remember the code for the possible exchange - TODO expiry these
                     (swap! store assoc
                            {:client-id client-id :code code}
                            {:created (java.util.Date.)
                             :cylon/identity identity})

                     (cookies-response {:status 302
                       :headers {"Location"
                                 (format
                                  ;; TODO: Replace this with the callback uri
                                  "%s?code=%s&state=%s"
                                  callback-uri code state
                                  )}
                       :cookies {"session-id" (->cookie session)}}))))
               ;; Fail
               {:status 302
                :headers {"Location" (format "%s" (path-for (:modular.bidi/routes req) ::get-authenticate-form))}
                :body "Try again"})))

         wrap-params wrap-cookies s/with-fn-validation)

     ::get-totp-code
     (fn [req]
       {:status 200
        :body (html
               [:h1 "Please can I have your auth code"]

               (let [secret (get-session-value req "session-id" (:session-store this) :totp-secret)]
                 [:div


                  [:form {:method :post
                          :action (path-for (:modular.bidi/routes req)
                                            ::post-totp-code)}
                   [:input {:type "text" :id "code" :name "code"}]
                   [:input {:type "submit"}]]

                  [:p "(Hint, maybe it's something like... this ? " (totp-token secret) ")"]]))})

     ::post-totp-code
     (-> (fn [req]
           (let [code (-> req :form-params (get "code"))
                 secret (get-session-value req "session-id" (:session-store this) :totp-secret)
                 ]
             (if (= code (totp-token secret))
               ;; Success, set up the exchange
               (let [session (get-session (:session-store this) (get-cookie-value req "session-id"))
                     client-id (get session :client-id)
                     _ (infof "Looking up app with client-id %s yields %s" client-id (lookup-application+ (:application-registry this) client-id))
                     {:keys [callback-uri] :as application}
                     (lookup-application+ (:application-registry this) client-id)
                     state (get session :state)
                     identity (get session :cylon/identity)
                     code (str (java.util.UUID/randomUUID))]

                 ;; Remember the code for the possible exchange - TODO expire these
                 (swap! store assoc
                        {:client-id client-id :code code}
                        {:created (java.util.Date.)
                         :cylon/identity identity})

                 {:status 302
                  :headers {"Location"
                            (format "%s?code=%s&state=%s" callback-uri code state)}})

               ;; Failed, have another go!
               {:status 302
                :headers {"Location"
                          (path-for (:modular.bidi/routes req) ::get-totp-code)}
                }

               )))
         wrap-params wrap-cookies s/with-fn-validation)

     ::exchange-code-for-access-token
     (-> (fn [req]
           (let [params (:form-params req)
                 code (get params "code")
                 client-id (get params "client_id")
                 client-secret (get params "client_secret")
                 application (lookup-application+ (:application-registry this) client-id)]

             (if (not= (:client-secret application) client-secret)
               {:status 400 :body "Invalid request - bad secret"}

               (if-let [{identity :cylon/identity}
                        (get @store
                             ;; I don't think this key has to include client-id
                             ;; - it can just be 'code'.
                             {:client-id client-id :code code})]

                 (let [{access-token :cylon.session/key}
                       (create-session! (:access-token-store this) {:scopes #{:superuser/read-users :repo :superuser/gist :admin}})
                       claim {:iss iss
                              :sub identity
                              :aud client-id
                              :exp (plus (now) (days 1)) ; expiry
                              :iat (now)}]

                   (info "Claim is %s" claim)

                   {:status 200
                    :body (encode {"access_token" access-token
                                   "scope" "repo gist openid profile email"
                                   "token_type" "Bearer"
                                   "expires_in" 3600
                                   ;; TODO Refresh token (optional)
                                   ;; ...
                                   ;; OpenID Connect ID Token
                                   "id_token" (-> claim
                                                  jwt
                                                  (sign :HS256 "secret") to-str)
                                   })})
                 {:status 400
                  :body "Invalid request - unknown code"}))))
         wrap-params s/with-fn-validation)})

  (routes [this]
    ["/" {"authorize" {:get ::authorize}
          "login" {:get ::get-authenticate-form
                   :post ::post-authenticate-form}
          "totp" {:get ::get-totp-code
                  :post ::post-totp-code}
          "access_token" {:post ::exchange-code-for-access-token}}])

  (uri-context [this] "/login/oauth"))

(defn new-client-authorizer-webapp [& {:as opts}]
  (component/using
   (->> opts
        (merge {:store (atom {})})
        (s/validate {:scopes {s/Keyword {:description s/Str}}
                     :store s/Any
                     :iss s/Str ; uri actually, see openid-connect ch 2.
                     })
        map->ClientAuthorizerWebApp)
   [:access-token-store
    :session-store
    :user-domain
    :application-registry]))