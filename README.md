***What does WebImageView do?***

-   Cache images from web onto local disk in the background automatically

-   Cache images from web in memory with SoftReference<Bitmap>, which result in smoothness when you use WebImage in a ListView

-   Hide all the fuzzy details in the class

***TODOs***

-   Use MD5 digest of the URL of the image that is cached on local disk as the file name instead of the original file name. This can avoid mistaking pictures with same name from different domain name.

-   Change the comments into English

-   Add more comments