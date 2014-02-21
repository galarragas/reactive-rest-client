

type Key = Array[Byte]

def createKey(first: String, second: String, third: String) : Key = {
    def asBytes(value: Int) : Array[Byte] = ???

    val firstLength : Array[Byte] = asBytes(first.length)
    val secondLength : Array[Byte] = asBytes(second.length)
    val thirdLength : Array[Byte] = asBytes(third.length)

    val firstString : Array[Byte]= first.getBytes()
    val secondString : Array[Byte]= first.getBytes()
    val thirdString : Array[Byte]= first.getBytes()


    // (firstLength as bytes) (firstLength as bytes) (firstLength as bytes)    |  firstString secondString thirdString
    //    4 bytes                 4 bytes                 4 bytes          (13)       ???         ???           ???


     null
  }

def extractFields(key: Key) : (String, String, String) = ???

def getFirst(key: Key) : String = extractFields(key)._1
def getSecond(key: Key) : String = extractFields(key)._2
def getThird(key: Key) : String = extractFields(key)._3

val stefanoKey = createKey("stefano", "1", "2")
val stefano1Key = createKey("stefano1", "", "2")
