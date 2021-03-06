package net.foxopen.fox.entrypoint.servlets;

import net.foxopen.fox.App;
import net.foxopen.fox.ContextUCon;
import net.foxopen.fox.FoxRequest;
import net.foxopen.fox.FoxResponse;
import net.foxopen.fox.XFUtil;
import net.foxopen.fox.auth.AuthUtil;
import net.foxopen.fox.auth.AuthenticationContext;
import net.foxopen.fox.auth.AuthenticationResult;
import net.foxopen.fox.auth.StandardAuthenticationContext;
import net.foxopen.fox.entrypoint.CookieBasedFoxSession;
import net.foxopen.fox.entrypoint.FoxGlobals;
import net.foxopen.fox.entrypoint.FoxSession;
import net.foxopen.fox.entrypoint.ParamsDOMUtils;
import net.foxopen.fox.entrypoint.uri.RequestURIBuilder;
import net.foxopen.fox.entrypoint.ws.PathParamTemplate;
import net.foxopen.fox.ex.ExApp;
import net.foxopen.fox.ex.ExInternal;
import net.foxopen.fox.ex.ExInvalidThreadId;
import net.foxopen.fox.ex.ExModule;
import net.foxopen.fox.ex.ExServiceUnavailable;
import net.foxopen.fox.ex.ExSessionTimeout;
import net.foxopen.fox.ex.ExUserRequest;
import net.foxopen.fox.module.Mod;
import net.foxopen.fox.module.entrytheme.EntryTheme;
import net.foxopen.fox.thread.RequestContext;
import net.foxopen.fox.thread.StatefulXThread;
import net.foxopen.fox.thread.ThreadLockManager;
import net.foxopen.fox.thread.XThreadBuilder;
import net.foxopen.fox.thread.stack.ModuleCall;
import net.foxopen.fox.track.Track;
import net.foxopen.fox.track.TrackProperty;

import javax.servlet.http.HttpServletRequest;

// TODO - Guest access returns VALID result, needs better test to stop AuthRequired modules (should also enum that up)
public class FoxMainServlet
extends EntryPointServlet {

  public static final String SERVLET_PATH = "fox";

  public static final String APP_MNEM_PARAM_NAME = "app_mnem";
  private static final String MODULE_PARAM_NAME = "module_name";
  private static final String ENTRY_THEME_PARAM_NAME = "entry_theme";

  /** Entry GET request URIs should conform to one of these patterns */
  private static final PathParamTemplate THEME_ENTRY_PATH_TEMPLATE = new PathParamTemplate("/{" + APP_MNEM_PARAM_NAME + "}/{" + MODULE_PARAM_NAME + "}/{" + ENTRY_THEME_PARAM_NAME + "}");
  private static final PathParamTemplate MODULE_ENTRY_PATH_TEMPLATE = new PathParamTemplate("/{" + APP_MNEM_PARAM_NAME + "}/{" + MODULE_PARAM_NAME + "}");

  /** Form POST request URIs should conform to this pattern */
  private static final PathParamTemplate APP_MNEM_SUFFIX_PATH_TEMPLATE = new PathParamTemplate("/{" + APP_MNEM_PARAM_NAME + "}/");

  public static final String MAIN_CONNECTION_NAME = "MAIN";
  private static final String RESUME_PARAM_NAME = "resume";
  private static final String RESUME_PARAM_TRUE_VALUE = "1";
  private static final String THREAD_ID_PARAM_NAME = "thread_id";

  public static String buildGetEntryURI(RequestURIBuilder pRequestURIBuilder, String pAppMnem, String pModuleName) {
    return buildGetEntryURI(pRequestURIBuilder, pAppMnem, pModuleName, null);
  }

  public static String buildGetEntryURI(RequestURIBuilder pRequestURIBuilder, String pAppMnem, String pModuleName, String pEntryTheme) {
    pRequestURIBuilder.setParam(APP_MNEM_PARAM_NAME, pAppMnem);
    pRequestURIBuilder.setParam(MODULE_PARAM_NAME, pModuleName);
    pRequestURIBuilder.setParam(ENTRY_THEME_PARAM_NAME, pEntryTheme);
    return pRequestURIBuilder.buildServletURI(SERVLET_PATH, pEntryTheme != null ? THEME_ENTRY_PATH_TEMPLATE : MODULE_ENTRY_PATH_TEMPLATE);
  }

  public static String buildThreadResumeEntryURI(RequestURIBuilder pRequestURIBuilder, String pThreadId, String pAppMnem) {
    pRequestURIBuilder.setParam(THREAD_ID_PARAM_NAME, pThreadId);
    pRequestURIBuilder.setParam(APP_MNEM_PARAM_NAME, pAppMnem);
    pRequestURIBuilder.setParam(RESUME_PARAM_NAME, RESUME_PARAM_TRUE_VALUE);
    return pRequestURIBuilder.buildServletURI(SERVLET_PATH, APP_MNEM_SUFFIX_PATH_TEMPLATE);
  }

  public static String buildFormPostDestinationURI(RequestURIBuilder pRequestURIBuilder, String pAppMnem) {
    pRequestURIBuilder.setParam(APP_MNEM_PARAM_NAME, pAppMnem);
    return pRequestURIBuilder.buildServletURI(FoxMainServlet.SERVLET_PATH, APP_MNEM_SUFFIX_PATH_TEMPLATE);
  }

  public static String getAppMnemFromRequestPath(HttpServletRequest pRequest) {
    return XFUtil.pathPopHead(new StringBuilder(XFUtil.nvl(pRequest.getPathInfo())), true);
  }

  /**
   * Determines if the given request is being used to resume an existing thread, i.e. as part of a modeless popup.
   * @param pRequest Request to check.
   * @return True if the request is a "thread resume" request.
   */
  public static boolean isThreadResumeRequest(HttpServletRequest pRequest) {
    return "GET".equals(pRequest.getMethod()) && pRequest.getParameterMap().containsKey(RESUME_PARAM_NAME);
  }

  @Override
  protected String establishAppMnem(HttpServletRequest pRequest) {
    return getAppMnemFromRequestPath(pRequest);
  }

  @Override
  protected String getTrackElementName(RequestContext pRequestContext) {
    return "FoxHttp" + XFUtil.initCap(pRequestContext.getFoxRequest().getHttpRequest().getMethod());
  }

  @Override
  public FoxSession establishFoxSession(RequestContext pRequestContext) {
    return CookieBasedFoxSession.getOrCreateFoxSession(pRequestContext);
  }

  @Override
  public void processGet(RequestContext pRequestContext) {
    processHttpRequest(pRequestContext);
  }

  @Override
  public void processPost(RequestContext pRequestContext) {
    processHttpRequest(pRequestContext);
  }

  @Override
  protected String getContextUConInitialConnectionName() {
    return MAIN_CONNECTION_NAME;
  }

  private void processHttpRequest(RequestContext pRequestContext) {

    FoxRequest lFoxRequest = pRequestContext.getFoxRequest();
    ContextUCon lContextUCon = pRequestContext.getContextUCon();

    String lThreadId = XFUtil.nvl(lFoxRequest.getParameter(THREAD_ID_PARAM_NAME)).trim();

    FoxResponse lFoxResponse;
    try {
      String lClientInfo = AuthUtil.getClientInfoNVP(pRequestContext.getFoxRequest());

      if(XFUtil.isNull(lThreadId)){
        //No ID provided - create a new thread (avoid the locking mechanism - new threads don't need to be locked)
        lFoxResponse = createNewThread(pRequestContext, lClientInfo);

        //Validates all transactions except the MAIN transaction are committed
        lContextUCon.closeAllRetainedConnections();

        //Commit the MAIN connection - commits all work done by thread
        lContextUCon.commit(MAIN_CONNECTION_NAME);
      }
      else {
        //Authenticate, lock thread and run action using the ThreadLockManager
        lFoxResponse = resumeThread(pRequestContext, lThreadId, lClientInfo);
      }
    }
    catch (Throwable th) {
      throw new ExInternal("Error processing request", th);
    }

    Track.pushInfo("SendResponse");
    try {
      lFoxResponse.respond(lFoxRequest);
    }
    finally {
      Track.pop("SendResponse");
    }
  }

  private FoxResponse resumeThread(RequestContext pRequestContext, String pThreadId, final String pClientInfo)
  throws ExServiceUnavailable, ExApp, ExUserRequest {

    Track.setProperty(TrackProperty.THREAD_ID, pThreadId);

    ThreadLockManager<FoxResponse> lThreadLockManager = new ThreadLockManager<>(pThreadId, false);
    try {
      return lThreadLockManager.lockAndPerformAction(pRequestContext, new ThreadLockManager.LockedThreadRunnable<FoxResponse>() {
        @Override
        public FoxResponse doWhenLocked(RequestContext pRequestContext, StatefulXThread pXThread) {

          FoxResponse lFoxResponse;
          try {
            //Check the database WUS for the thread is still valid
            AuthenticationResult lAuthResult;
            Track.pushInfo("RequestAuthentication");
            try {
              AuthenticationContext lAuthContext = pXThread.getAuthenticationContext();
              lAuthResult = lAuthContext.verifySession(pRequestContext, pClientInfo, "TODO thread last module");
            }
            finally {
              Track.pop("RequestAuthentication");
            }

            //TODO - if thread has 0 state calls, interpret as a timeout and redirect to timeout screen (so UX is improved when user exits, hits back and tries another action)

            if (!lAuthResult.getCode().isAuthenticationSucceeded()){

              //TODO disallow guest access for auth required modules
              App lCurrentApp = FoxGlobals.getInstance().getFoxEnvironment().getAppByMnem(pXThread.getThreadAppMnem(), true);

              Track.info("SessionInvalid", "Session is no longer valid (" + lAuthResult.getCode().toString() + "); handling timeout");

              lFoxResponse = handleTimeout(pRequestContext, lCurrentApp);
            }
            else {
              //Note auth result may be VALID, GUEST or PASSWORD_EXPIRED - in all cases a resume is allowed, because authentication logic is checked on thread create

              FoxRequest lFoxRequest = pRequestContext.getFoxRequest();
              if(RESUME_PARAM_TRUE_VALUE.equals(lFoxRequest.getParameter(RESUME_PARAM_NAME))) {
                //If this is an external resume, do not attempt to process an action
                lFoxResponse = pXThread.processExternalResume(pRequestContext);
              }
              else {
                //Not an external resume so must be an action request (i.e. form churn) - process the action
                String lActionName = lFoxRequest.getParameter("action_name");
                String lContextRef = lFoxRequest.getParameter("context_ref");
                lFoxResponse = pXThread.processAction(pRequestContext, lActionName, lContextRef, lFoxRequest.getHttpRequest().getParameterMap());
              }
            }
          }
          catch (ExServiceUnavailable | ExApp | ExUserRequest e) {
            throw new ExInternal("Failed to resume thread", e);
          }

          return lFoxResponse;
        }
      });
    }
    catch (ExInvalidThreadId e) {
      Track.recordRedirectedException("Thread Resume Invalid Thread Id", e);
      Track.alert("InvalidThreadIdTimeout", "Redirecting to timeout module and forcing new session due to invalid thread ID");

      //Thread ID is invalid, probably because the user has posted a form with a very old ID in it
      //Connection will have been closed by the thread lock manager, so we need a new one to handle the timeout at this point
      ContextUCon lContextUCon = pRequestContext.getContextUCon();
      lContextUCon.pushConnection(MAIN_CONNECTION_NAME);

      //Move the session ID along in case it's stale
      pRequestContext.getFoxSession().forceNewFoxSessionID(pRequestContext, "");
      FoxResponse lFoxResponse = handleTimeout(pRequestContext, pRequestContext.getRequestApp());
      lContextUCon.commit(MAIN_CONNECTION_NAME);

      //Connection pop will be done by EntryPointServlet
      return lFoxResponse;
    }
  }

  private EntryTheme establishEntryThemeFromRequest(HttpServletRequest pRequest) {
    StringBuilder lURITail = new StringBuilder();
    XFUtil.pathPushTail(lURITail, pRequest.getPathInfo());

    String lAppMnem = XFUtil.nvl(pRequest.getParameter("app_mnem"), XFUtil.pathPopHead(lURITail, true));
    App lApp;
    try {
      lApp = FoxGlobals.getInstance().getFoxEnvironment().getAppByMnem(lAppMnem, true);
    }
    catch (ExApp | ExServiceUnavailable e) {
      throw new ExInternal("Cannot get app " + lAppMnem, e);
    }

    String lModuleName = XFUtil.nvl(XFUtil.nvl(pRequest.getParameter("module"), XFUtil.pathPopHead(lURITail, true)), lApp.getDefaultModuleName());
    Mod lModule;
    try {
      lModule = lApp.getMod(lModuleName);
    }
    catch (ExUserRequest | ExApp | ExModule | ExServiceUnavailable e) {
      throw new ExInternal("Cannot get mod " + lModuleName, e);
    }

    String lThemeName = XFUtil.nvl(XFUtil.nvl(pRequest.getParameter("theme"), XFUtil.pathPopHead(lURITail, true)), lModule.getDefaultEntryThemeName());
    try {
      return lModule.getEntryTheme(lThemeName);
    }
    catch (ExUserRequest e) {
      throw new ExInternal("Cannot get theme " + lThemeName, e);
    }
  }

  private FoxResponse createNewThread(RequestContext pRequestContext, String pClientInfo)
  throws ExUserRequest {
    //No thread id - construct a new thread
    EntryTheme lEntryTheme = establishEntryThemeFromRequest(pRequestContext.getFoxRequest().getHttpRequest());
    App lApp = lEntryTheme.getModule().getApp();

    if(lApp.isEntryThemeSecurityOn() && !lEntryTheme.isExternallyAccessible()) {
      //TODO correct status code
      throw new ExUserRequest("Entry theme not externally accessible");
    }

    FoxResponse lFoxResponse;
    try {
      // Attempt to get Auth Context/Result, re-trying with a new FOX Session if the cookie one was timed out/invalid
      AuthenticationContext lAuthContext;
      AuthenticationResult lAuthResult;
      Track.pushInfo("RequestAuthentication");
      try {
        lAuthContext = lEntryTheme.getAuthType().processBeforeEntry(pRequestContext);
        lAuthResult = lAuthContext.verifySession(pRequestContext, pClientInfo, "(direct entry)");
      }
      catch (ExSessionTimeout e) {
        //Sent FOX session ID was stale/expired
        if (lEntryTheme.getModule().isAuthenticationRequired()) {
          // If the module they're trying to access requires auth, throw this error
          Track.info("SessionTimeout", "Session timed out and auth required");
          throw e;
        }
        else {
          // If the module they're trying has guest access, re-try with a fresh FOX Session
          Track.info("SessionTimeout", "Session timed out but auth not required; creating new FOX session");
          forceNewFoxSession(pRequestContext);

          lAuthContext = lEntryTheme.getAuthType().processBeforeEntry(pRequestContext);
          lAuthResult = lAuthContext.verifySession(pRequestContext, pClientInfo, "(direct entry)");
        }
      }
      finally {
        Track.pop("RequestAuthentication");
      }

      boolean lNotAuthenticated = !lAuthResult.getCode().isAuthenticationSucceeded() || lAuthResult.getCode() == AuthenticationResult.Code.GUEST;

      if (lNotAuthenticated && lEntryTheme.getModule().isAuthenticationRequired()) {
        // TODO TIMEOUT HANDLING, differentiate between authenticated/guest
        Track.info("SessionTimeout", "Auth result invalid and authentication required on current entry theme; redirecting to timeout module");
        lFoxResponse = handleTimeout(pRequestContext, lApp);
      }
      else if (lAuthResult.getCode() == AuthenticationResult.Code.PASSWORD_EXPIRED && !lEntryTheme.isAllowedPasswordExpiredAccess()) {
        Track.info("PasswordExpired", "Auth result password expired and current entry theme does not allow access");
        lFoxResponse = handlePasswordExpiry(pRequestContext, lApp, lAuthContext);
      }
      else {
        //Authentication passed - create the new thread
        XThreadBuilder lXThreadBuilder = new XThreadBuilder(lApp.getMnemonicName(), lAuthContext);

        StatefulXThread lNewXThread =  lXThreadBuilder.createXThread(pRequestContext);

        //Start thread using entry params and entry theme from URL
        ModuleCall.Builder lModuleCallBuilder = new ModuleCall.Builder(lEntryTheme);
        lModuleCallBuilder.setParamsDOM(ParamsDOMUtils.paramsDOMFromRequest(pRequestContext.getFoxRequest()));

        lFoxResponse = lNewXThread.startThread(pRequestContext, lModuleCallBuilder, true);
      }
    }
    catch (ExSessionTimeout e) {
      // Force a new FOX Session on response so subsequent requests don't have the same problem
      Track.info("SessionTimeout", "Forcing new session and handling timeout");
      forceNewFoxSession(pRequestContext);

      lFoxResponse = handleTimeout(pRequestContext, lApp);
    }

    return lFoxResponse;
  }

  /**
   * Forces a new FoxSession <i>on the RequestContext</i>. This will NOT attempt to move the session ID along. This should
   * be used to obliterate an invalid session without having to create a new RequestContext.
   * @param pRequestContext RequestContext to modify.
   */
  private void forceNewFoxSession(RequestContext pRequestContext) {
    pRequestContext.forceNewFoxSession(CookieBasedFoxSession.createNewSession(pRequestContext, false, null));
  }

  /**
   * Creates a new thread, with the given app's timeout module as the entry point.
   * @param pRequestContext Current RequestContext.
   * @param pRequestApp The current App, to get the timeout module from
   * @return FoxResponse from the timeout module's entry theme.
   * @throws ExUserRequest If the timeout modules's entry theme markup is invalid.
   */
  private FoxResponse handleTimeout(RequestContext pRequestContext, App pRequestApp) throws ExUserRequest {
    EntryTheme lTimeoutEntryTheme = pRequestApp.getTimeoutMod().getDefaultEntryTheme();
    //Redirect with a "guest user" authentication context
    return handleRedirect(pRequestContext, pRequestApp, lTimeoutEntryTheme, new StandardAuthenticationContext(pRequestContext));
  }

  /**
   * Creates a new thread, with the given app's password expired module as the entry point.
   * @param pRequestContext Current RequestContext.
   * @param pRequestApp The current App, to get the timeout module from
   * @param pAuthenticationContext AuthenticationContext for the session with an expired password.
   * @return FoxResponse from the password expired module's entry theme.
   */
  private FoxResponse handlePasswordExpiry(RequestContext pRequestContext, App pRequestApp, AuthenticationContext pAuthenticationContext) {
    EntryTheme lExpiredPasswordEntryTheme = pRequestApp.getPasswordExpiredEntryTheme();
    return handleRedirect(pRequestContext, pRequestApp, lExpiredPasswordEntryTheme, pAuthenticationContext);
  }

  /**
   * Creates a new thread to redirect the user to.
   * @param pRequestContext Current RequestContext.
   * @param pRequestApp Current App.
   * @param pEntryTheme Entry theme to start new thread in.
   * @param pAuthenticationContext AuthenticationContext to assign to the new thread.
   * @return FoxResponse from running the new thread's entry theme.
   */
  private FoxResponse handleRedirect(RequestContext pRequestContext, App pRequestApp, EntryTheme pEntryTheme, AuthenticationContext pAuthenticationContext) {
    XThreadBuilder lXThreadBuilder = new XThreadBuilder(pRequestApp.getMnemonicName(), pAuthenticationContext);

    //TODO - mark thread as not requiring persistence

    StatefulXThread lNewThread = lXThreadBuilder.createXThread(pRequestContext);
    return lNewThread.startThread(pRequestContext, new ModuleCall.Builder(pEntryTheme), true);
  }
}
