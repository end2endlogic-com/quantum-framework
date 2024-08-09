package com.e2eq.framework.model.persistent.interfaces;

import java.util.Date;

public interface Archivable {
   /**
    The UTC date after which this object should be archived
    @return the date after which the object should be archived
    */
   Date getArchiveDate();
   void setArchiveDate(Date archiveDate);

   /**
    @return mark the record for archive
    */
   boolean isMarkedForArchive();
   void setMarkedForArchive(boolean markArchive);

   /**
    @return true indicates that the record has been archived
    */
   boolean isArchived();
   void setArchived(boolean archived);

}
