package com.e2eq.framework.rest.filters;


//@Alternative
//@Priority(1)
//@ApplicationScoped
public class CustomAwareJWTAuthMechanism {

}
/*
public class CustomAwareJWTAuthMechanism implements HttpAuthenticationMechanism {
   @Inject
   JWTAuthMechanism delegate;

   @Override
   public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
      // do some custom action and delegate
      return delegate.authenticate(context, identityProviderManager);
   }

   @Override
   public Uni<ChallengeData> getChallenge(RoutingContext context) {
      return delegate.getChallenge(context);
   }

   @Override
   public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
      return delegate.getCredentialTypes();
   }

   @Override
   public HttpCredentialTransport getCredentialTransport() {
      return delegate.getCredentialTransport();
   }
}
eyJraWQiOiIvcHJpdmF0ZUtleS5wZW0iLCJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tL2lzc3VlciIsInN1YiI6ImFkbWluQGIyYmludGVncmF0b3IuY29tIiwiaWF0IjoxNjk5ODE5OTA4LCJhdWQiOiJiMmJpLWFwaS1jbGllbnQiLCJleHAiOjE2OTk4MjcxMDgsImdyb3VwcyI6WyJhZG1pbiJdLCJvcmdSZWZOYW1lIjoiYjJiaW50ZWdyYXRvci5jb20iLCJ0ZW5hbnRJZCI6ImIyYmludGVncmF0b3ItY29tIiwiZGVmYXVsdFJlYWxtIjoiYjJiaW50ZWdyYXRvci1jb20iLCJhY2NvdW50SWQiOiIwMDAwMDAwMDAwIiwic2NvcGUiOiJhdXRoVG9rZW4iLCJyZWFsbU92ZXJyaWRlcyI6eyJzZWN1cml0eSI6ImIyYmkiLCJzaWdudXAiOiJiMmJpIn0sImp0aSI6ImY3MDZkZjFmLWU2MGYtNDg0Ni1hNGYwLTA1MjE2OWE2OTAzZSJ9.CC_bvVZaifSMavd4S16uK5ht_wgvlR1oFhD4k43P7063mHDJOnbO1hNXYWecq41cnmDEsbC1tTfZWyIzupBm5TVBlZIrGCf-YOzCFMwH6N-InbXyupdtbCusH7p4bE33Akx5w82jt3Sd28FoDVt55JS0npYP8PeNm1r60rN-58eptq0xIJttVF1euBEBqFRImP9CWSDAx0BZlX_RTsH-4rKxJJvJOFISC5lsYQgFSKkWBusj2Vkn6_tu5z7xgaV6gK10H72cPKTUblxTT-RRssBtfCvr8o2bdwjQgWX2YM2Q6tt7IWGYefdFdRBVr4IskzBGacny1bP6sDlSFZxGGQ */
