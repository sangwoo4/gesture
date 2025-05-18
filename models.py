from sqlalchemy import Column, Integer, String
from database import Base

class File(Base):
    __tablename__ = 'files'

    id = Column(Integer, primary_key=True, index=True)
    code = Column(String(255), unique=True, index=True)
    Model = Column(String(255), nullable=False)
    Train_Data = Column(String(255), nullable=False)
    Test_Data = Column(String(255), nullable=False)
    Combined_Train_Data = Column(String(255), nullable=False)
    Combined_Test_Data = Column(String(255), nullable=False)

