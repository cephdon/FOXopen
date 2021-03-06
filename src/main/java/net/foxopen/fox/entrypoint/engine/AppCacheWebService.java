package net.foxopen.fox.entrypoint.engine;

import net.foxopen.fox.FoxRequest;
import net.foxopen.fox.banghandler.FlushBangHandler;
import net.foxopen.fox.entrypoint.ws.EndPoint;
import net.foxopen.fox.entrypoint.ws.GenericWebServiceResponse;
import net.foxopen.fox.entrypoint.ws.PathParamTemplate;
import net.foxopen.fox.entrypoint.ws.WebService;
import net.foxopen.fox.entrypoint.ws.WebServiceAuthDescriptor;
import net.foxopen.fox.entrypoint.ws.WebServiceAuthType;
import net.foxopen.fox.entrypoint.ws.WebServiceResponse;
import net.foxopen.fox.thread.RequestContext;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * WebService for managing the application cache.
 */
public class AppCacheWebService
implements WebService {

  @Override
  public String getName() {
    return "appCache";
  }

  @Override
  public WebServiceAuthDescriptor getAuthDescriptor() {
    return new WebServiceAuthDescriptor(false, WebServiceAuthType.TOKEN);
  }

  @Override
  public String getRequiredConnectionPoolName(FoxRequest pFoxRequest) {
    return null;
  }

  @Override
  public Collection<? extends EndPoint> getAllEndPoints() {
    return Collections.singleton(new FlushEndPoint());
  }

  /**
   * WebService equivalent to !FLUSH
   */
  private static class FlushEndPoint
  implements EndPoint {

    @Override
    public String getName() {
      return "flush";
    }

    @Override
    public PathParamTemplate getPathParamTemplate() {
      return null;
    }

    @Override
    public Collection<String> getMandatoryRequestParamNames() {
      return Collections.emptySet();
    }

    @Override
    public Collection<String> getAllowedHttpMethods() {
      return Collections.singleton("GET");
    }

    @Override
    public WebServiceResponse respond(RequestContext pRequestContext, Map<String, String> pParamMap, String pHttpMethod, WebServiceResponse.Type pDesiredResponseType) {
      FlushBangHandler.flushApplicationCache();
      return new GenericWebServiceResponse(Collections.singletonMap("message", "FLUSH success"));
    }
  }
}
