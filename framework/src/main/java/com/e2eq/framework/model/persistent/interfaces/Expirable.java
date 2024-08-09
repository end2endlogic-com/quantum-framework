package com.e2eq.framework.model.persistent.interfaces;

import java.util.Date;

/**
  Identifies that this class can expire i.e. its assumed that the class is
 persistent and that by implementing this interface its indicated that the
 corresponding persistent representation will eventually be deleted due to
 expiring.  The main goal for this is to leverage NoSQL databases such as Mongodb
 and DynamoDB which both provide means to "expire" data.  S3 also has this concept as well.
 */
public interface Expirable {
   /**
    Get the UTC date and time after which this object should be considered "expired"
    @return the Date and time in UTC time that this object should expire.
    */
   Date getExpireDate();

   /**
    Set the time in UTC time after which the object should be considered expired.
    @param expireDateTime the UTC time
    */
   void setExpireDate(Date expireDateTime);

   /**
    Checks the current system time compared to the UTC time set to determine if the object is
    expired or nto.
    @return  true it is expired, false it's not
    */
   boolean isExpired();

   /**
    Set this object to be removed by the system.  The implementation this will vary by the underlying
    storage mechanism, ie. Mongo vs. S3 vs. Dynamo
    */
   void setMarkedForDelete(boolean markForDelete);

   /**
    Determines if this object is already marked or not.
    */
   boolean isMarkedForDelete();
}
