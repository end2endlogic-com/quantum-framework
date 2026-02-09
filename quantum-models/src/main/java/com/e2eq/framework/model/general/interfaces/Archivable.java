package com.e2eq.framework.model.general.interfaces;

import java.util.Date;

public interface Archivable {
   /**
    The UTC date after which this object should be archived
    @return the date after which the object should be archived
    */
   Date getArchiveDate();
   void setArchiveDate(Date archiveDate);

   /**
    * Indicates whether the record is marked for archive.
    *
    * @return true if the record is marked for archive
    */
   boolean isMarkedForArchive();
   void setMarkedForArchive(boolean markArchive);

   /**
    * Indicates whether the record has been archived.
    *
    * @return true if the record has been archived
    */
   boolean isArchived();
   void setArchived(boolean archived);

}
